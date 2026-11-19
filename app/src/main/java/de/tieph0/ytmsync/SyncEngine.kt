package de.tieph0.ytmsync

import android.content.Context
import kotlinx.coroutines.delay

/**
 * Two-way, add-only sync between the main channel and a brand channel.
 *  - Likes: everything liked on one side gets liked on the other.
 *  - Playlists (own ones, matched by title): missing playlists are created
 *    (private), missing songs are appended. Nothing is ever deleted.
 *
 * Writes per run are capped so a huge first sync can't run into the
 * WorkManager time limit; the rest is picked up by the next run.
 */
class SyncEngine(
    context: Context,
    private val isStopped: () -> Boolean,
    private val progress: suspend (String) -> Unit,
) {
    companion object {
        const val MAX_WRITES_PER_RUN = 250
        private const val WRITE_DELAY_MS = 300L
        private const val CHUNK = 50
    }

    private val prefs = Prefs(context)
    private var budget = MAX_WRITES_PER_RUN
    private var pending = 0

    private suspend fun write(block: () -> Unit): Boolean {
        if (budget <= 0 || isStopped()) {
            pending++
            return false
        }
        block()
        budget--
        delay(WRITE_DELAY_MS)
        return true
    }

    suspend fun run(): String {
        val cookie = prefs.cookie ?: throw AuthException("Nicht angemeldet")
        val brand = prefs.brandId ?: throw IllegalStateException("Kein Brand-Kanal ausgewählt")
        val api = YtmClient(cookie)

        progress("Prüfe Kanäle …")
        val mainName = runCatching { api.accountName(null) }.getOrNull()
        val brandName = runCatching { api.accountName(brand) }.getOrNull()
        if (mainName != null && mainName == brandName) {
            throw IllegalStateException(
                "Beide Seiten zeigen auf denselben Kanal ($mainName). " +
                    "Melde dich neu an und wähle im Login deinen Hauptkanal."
            )
        }
        mainName?.let { prefs.mainName = it }

        // ---- likes
        progress("Lese Likes …")
        val likesMain = api.playlistVideoIds("LM", null)
        val likesBrand = api.playlistVideoIds("LM", brand)
        if (likesMain.isEmpty() && likesBrand.isEmpty()) {
            throw IllegalStateException("Keine Likes gefunden – Login prüfen")
        }

        val toMain = likesBrand - likesMain
        val toBrand = likesMain - likesBrand
        var likedMain = 0
        var likedBrand = 0
        if (toMain.isNotEmpty() || toBrand.isNotEmpty()) progress("Übertrage ${toMain.size + toBrand.size} Likes …")
        for (id in toMain) if (write { api.like(id, null) }) likedMain++
        for (id in toBrand) if (write { api.like(id, brand) }) likedBrand++

        // ---- playlists
        var created = 0
        var added = 0
        if (prefs.syncPlaylists) {
            progress("Lese Playlists …")
            val plMain = ownPlaylists(api, null)
            val plBrand = ownPlaylists(api, brand)
            val r1 = syncPlaylists(api, plBrand, plMain, null, "Hauptkanal")
            val r2 = syncPlaylists(api, plMain, plBrand, brand, "Brand")
            created = r1.first + r2.first
            added = r1.second + r2.second
        }

        val parts = mutableListOf("Likes ${likesMain.size}/${likesBrand.size}")
        if (likedMain + likedBrand > 0) parts += "+$likedMain → Haupt, +$likedBrand → Brand"
        if (created > 0) parts += "$created Playlists neu"
        if (added > 0) parts += "$added Songs ergänzt"
        if (pending > 0) parts += "$pending ausstehend (nächster Lauf)"
        if (likedMain + likedBrand + created + added == 0 && pending == 0) parts += "alles synchron"
        return parts.joinToString(" · ")
    }

    /** title -> (playlistId, videoIds) for playlists owned by [who]. */
    private fun ownPlaylists(api: YtmClient, who: String?): MutableMap<String, Pair<String, MutableSet<String>>> {
        val all = api.libraryPlaylists(who)
        val owned = all.filter { it.owned }.ifEmpty {
            if (all.isNotEmpty()) prefs.log("Hinweis: Eigentümer nicht erkennbar – nutze alle ${all.size} Playlists")
            all
        }
        val out = LinkedHashMap<String, Pair<String, MutableSet<String>>>()
        for (p in owned) {
            if (out.containsKey(p.title)) continue
            out[p.title] = p.id to api.playlistVideoIds(p.id, who).toMutableSet()
        }
        return out
    }

    /** Copies playlists/songs from [src] into [dst]. Returns (created, songsAdded). */
    private suspend fun syncPlaylists(
        api: YtmClient,
        src: Map<String, Pair<String, Set<String>>>,
        dst: MutableMap<String, Pair<String, MutableSet<String>>>,
        dstWho: String?,
        dstLabel: String,
    ): Pair<Int, Int> {
        var created = 0
        var added = 0
        for ((title, srcPl) in src) {
            val srcIds = srcPl.second
            val target = dst[title]
            if (target == null) {
                if (srcIds.isEmpty()) continue
                progress("Erstelle '$title' in $dstLabel …")
                val ids = srcIds.toList()
                var newId: String? = null
                if (write { newId = api.createPlaylist(title, ids.take(CHUNK), dstWho) }) {
                    created++
                    added += minOf(CHUNK, ids.size)
                    val id = newId!!
                    val set = ids.take(CHUNK).toMutableSet()
                    dst[title] = id to set
                    for (chunk in ids.drop(CHUNK).chunked(CHUNK)) {
                        if (write { api.addToPlaylist(id, chunk, dstWho) }) {
                            set += chunk
                            added += chunk.size
                        }
                    }
                }
            } else {
                val missing = (srcIds - target.second).toList()
                if (missing.isEmpty()) continue
                progress("Ergänze ${missing.size} Songs in '$title' ($dstLabel) …")
                for (chunk in missing.chunked(CHUNK)) {
                    if (write { api.addToPlaylist(target.first, chunk, dstWho) }) {
                        target.second += chunk
                        added += chunk.size
                    }
                }
            }
        }
        return created to added
    }
}
