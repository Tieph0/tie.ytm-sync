package de.tieph0.ytmsync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class AuthException(message: String) : Exception(message)

data class Channel(val name: String, val handle: String?, val pageId: String?)
data class LibPlaylist(val id: String, val title: String, val owned: Boolean)

/**
 * Minimal client for the internal YouTube Music API ("InnerTube", WEB_REMIX),
 * modelled after ytmusicapi. Authenticates with the browser cookie (SAPISIDHASH).
 * Brand accounts are addressed via context.user.onBehalfOfUser = pageId.
 */
class YtmClient(private val cookie: String) {

    companion object {
        private const val ORIGIN = "https://music.youtube.com"
        private const val BASE = "$ORIGIN/youtubei/v1/"
        private const val KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
        private val JSON_TYPE = "application/json".toMediaType()
        private val SKIP_PLAYLISTS = setOf("LM", "SE")

        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        fun parseCookie(raw: String): Map<String, String> =
            raw.split(";").mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
            }.toMap()

        fun cookieLooksValid(raw: String?): Boolean {
            if (raw.isNullOrBlank()) return false
            val c = parseCookie(raw)
            return c.containsKey("__Secure-3PAPISID") || c.containsKey("SAPISID")
        }
    }

    private val sapisid: String = parseCookie(cookie).let { it["__Secure-3PAPISID"] ?: it["SAPISID"] }
        ?: throw AuthException("Cookie enthält kein SAPISID – bitte neu anmelden")

    private fun sapisidHash(): String {
        val ts = System.currentTimeMillis() / 1000
        val digest = MessageDigest.getInstance("SHA-1").digest("$ts $sapisid $ORIGIN".toByteArray())
        return "SAPISIDHASH ${ts}_" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun clientVersion(): String {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return "1.${f.format(Date())}.01.00"
    }

    /** POST to an InnerTube endpoint. [onBehalfOf] = brand account pageId or null for the main channel. */
    fun call(endpoint: String, body: JSONObject, onBehalfOf: String?, extraParams: String = ""): JSONObject {
        val user = JSONObject()
        if (!onBehalfOf.isNullOrBlank()) user.put("onBehalfOfUser", onBehalfOf)
        val context = JSONObject()
            .put("client", JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", clientVersion())
                .put("hl", "de")
                .put("gl", "DE"))
            .put("user", user)
        body.put("context", context)

        val request = Request.Builder()
            .url("$BASE$endpoint?alt=json&prettyPrint=false&key=$KEY$extraParams")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .header("User-Agent", UA)
            .header("Cookie", cookie)
            .header("Origin", ORIGIN)
            .header("X-Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("Authorization", sapisidHash())
            .header("X-Goog-AuthUser", "0")
            .build()

        http.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401) throw AuthException("Anmeldung abgelaufen (HTTP 401) – bitte neu anmelden")
            if (r.code == 403) throw AuthException("Zugriff verweigert (HTTP 403) – Login abgelaufen?")
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} bei $endpoint: ${text.take(160)}")
            return try {
                JSONObject(text)
            } catch (e: Exception) {
                throw IOException("Ungültige Antwort von $endpoint")
            }
        }
    }

    // ---------------------------------------------------------------- JSON helpers

    private fun forEachNode(node: Any?, action: (key: String, value: Any) -> Unit) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k) ?: continue
                    action(k, v)
                    forEachNode(v, action)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) forEachNode(node.opt(i), action)
        }
    }

    private fun findObjects(node: Any?, key: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        forEachNode(node) { k, v -> if (k == key && v is JSONObject) out += v }
        return out
    }

    private fun firstString(node: Any?, key: String): String? {
        var found: String? = null
        forEachNode(node) { k, v -> if (found == null && k == key && v is String && v.isNotBlank()) found = v }
        return found
    }

    private fun text(obj: JSONObject?): String? {
        if (obj == null) return null
        obj.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        val runs = obj.optJSONArray("runs") ?: return null
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
            .takeIf { it.isNotBlank() }
    }

    // ---------------------------------------------------------------- paging

    private sealed class Next {
        data class Command(val token: String) : Next()
        data class Legacy(val token: String) : Next()
    }

    private fun findNext(resp: JSONObject): Next? {
        for (cir in findObjects(resp, "continuationItemRenderer")) {
            val ep = cir.optJSONObject("continuationEndpoint") ?: continue
            ep.optJSONObject("continuationCommand")?.optString("token")?.takeIf { it.isNotBlank() }
                ?.let { return Next.Command(it) }
            val cmds = ep.optJSONObject("commandExecutorCommand")?.optJSONArray("commands") ?: continue
            for (i in 0 until cmds.length()) {
                cmds.optJSONObject(i)?.optJSONObject("continuationCommand")?.optString("token")
                    ?.takeIf { it.isNotBlank() }?.let { return Next.Command(it) }
            }
        }
        for (ncd in findObjects(resp, "nextContinuationData")) {
            ncd.optString("continuation").takeIf { it.isNotBlank() }?.let { return Next.Legacy(it) }
        }
        return null
    }

    /** Browses [browseId] and follows continuations, handing every page to [page]. */
    private fun browseAll(browseId: String, who: String?, page: (JSONObject) -> Int) {
        var resp = call("browse", JSONObject().put("browseId", browseId), who)
        val seen = mutableSetOf<String>()
        var pages = 0
        while (true) {
            val added = page(resp)
            val next = findNext(resp) ?: break
            val token = when (next) {
                is Next.Command -> next.token
                is Next.Legacy -> next.token
            }
            if (!seen.add(token) || (pages > 0 && added == 0) || ++pages > 300) break
            resp = when (next) {
                is Next.Command -> call("browse", JSONObject().put("continuation", token), who)
                is Next.Legacy -> call(
                    "browse", JSONObject().put("browseId", browseId), who,
                    "&ctoken=$token&continuation=$token&type=next"
                )
            }
        }
    }

    // ---------------------------------------------------------------- reading

    /** All videoIds in a playlist ("LM" = liked music). */
    fun playlistVideoIds(playlistId: String, who: String?): Set<String> {
        val ids = LinkedHashSet<String>()
        browseAll("VL$playlistId", who) { resp ->
            val before = ids.size
            for (item in findObjects(resp, "musicResponsiveListItemRenderer")) {
                val vid = item.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotBlank() }
                    ?: firstString(item, "videoId")
                if (vid != null) ids += vid
            }
            ids.size - before
        }
        return ids
    }

    /** Playlists in the library of the given channel. */
    fun libraryPlaylists(who: String?): List<LibPlaylist> {
        val result = LinkedHashMap<String, LibPlaylist>()
        browseAll("FEmusic_liked_playlists", who) { resp ->
            val before = result.size
            val items = findObjects(resp, "musicTwoRowItemRenderer") +
                findObjects(resp, "musicResponsiveListItemRenderer")
            for (item in items) {
                val title = text(item.optJSONObject("title"))
                    ?: item.optJSONArray("flexColumns")?.optJSONObject(0)
                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.optJSONObject("text")?.let { text(it) }
                    ?: continue
                val browseId = item.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")?.optString("browseId")
                    ?: item.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)
                        ?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                        ?.optString("browseId")
                    ?: continue
                if (!browseId.startsWith("VL")) continue
                val id = browseId.removePrefix("VL")
                if (id in SKIP_PLAYLISTS || id.startsWith("RDCLAK")) continue
                var owned = false
                forEachNode(item) { k, v ->
                    if (k == "playlistEditorEndpoint" && v is JSONObject && v.optString("playlistId") == id) owned = true
                }
                result.putIfAbsent(id, LibPlaylist(id, title, owned))
            }
            result.size - before
        }
        return result.values.toList()
    }

    /** Name of the channel the request acts as (used to verify both sides differ). */
    fun accountName(who: String?): String? {
        val resp = call("account/account_menu", JSONObject(), who)
        val header = findObjects(resp, "activeAccountHeaderRenderer").firstOrNull() ?: return null
        val name = text(header.optJSONObject("accountName"))
        val handle = text(header.optJSONObject("channelHandle"))
        return listOfNotNull(name, handle).joinToString(" ").ifBlank { null }
    }

    /** Channels (incl. brand accounts) available to the logged-in Google account. */
    fun listChannels(): List<Channel> {
        val resp = call("account/accounts_list", JSONObject(), null)
        return findObjects(resp, "accountItem").mapNotNull { item ->
            val name = text(item.optJSONObject("accountName")) ?: return@mapNotNull null
            val handle = text(item.optJSONObject("channelHandle"))
            Channel(name, handle, firstString(item, "pageId"))
        }.distinctBy { it.name + it.pageId }
    }

    // ---------------------------------------------------------------- writing

    fun like(videoId: String, who: String?) {
        call("like/like", JSONObject().put("target", JSONObject().put("videoId", videoId)), who)
    }

    fun createPlaylist(title: String, videoIds: List<String>, who: String?): String {
        val body = JSONObject()
            .put("title", title)
            .put("description", "Synchronisiert mit YTM Sync")
            .put("privacyStatus", "PRIVATE")
            .put("videoIds", JSONArray(videoIds))
        val resp = call("playlist/create", body, who)
        return resp.optString("playlistId").takeIf { it.isNotBlank() }
            ?: throw IOException("Playlist '$title' konnte nicht erstellt werden")
    }

    fun addToPlaylist(playlistId: String, videoIds: List<String>, who: String?) {
        val actions = JSONArray()
        videoIds.forEach {
            actions.put(JSONObject()
                .put("action", "ACTION_ADD_VIDEO")
                .put("addedVideoId", it)
                .put("dedupeOption", "DEDUPE_OPTION_SKIP"))
        }
        val resp = call("browse/edit_playlist", JSONObject().put("playlistId", playlistId).put("actions", actions), who)
        if (!resp.optString("status").contains("SUCCEEDED")) {
            throw IOException("Hinzufügen zu Playlist fehlgeschlagen (${resp.optString("status")})")
        }
    }
}
