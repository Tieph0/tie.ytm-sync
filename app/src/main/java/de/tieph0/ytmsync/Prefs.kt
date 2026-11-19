package de.tieph0.ytmsync

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small wrapper around app-private SharedPreferences. */
class Prefs(context: Context) {
    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("ytmsync", Context.MODE_PRIVATE)

    var cookie: String?
        get() = sp.getString("cookie", null)
        set(v) = sp.edit().putString("cookie", v).apply()

    var mainName: String?
        get() = sp.getString("mainName", null)
        set(v) = sp.edit().putString("mainName", v).apply()

    var brandId: String?
        get() = sp.getString("brandId", null)
        set(v) = sp.edit().putString("brandId", v).apply()

    var brandName: String?
        get() = sp.getString("brandName", null)
        set(v) = sp.edit().putString("brandName", v).apply()

    var autoSync: Boolean
        get() = sp.getBoolean("autoSync", true)
        set(v) = sp.edit().putBoolean("autoSync", v).apply()

    var syncPlaylists: Boolean
        get() = sp.getBoolean("syncPlaylists", true)
        set(v) = sp.edit().putBoolean("syncPlaylists", v).apply()

    var wifiOnly: Boolean
        get() = sp.getBoolean("wifiOnly", false)
        set(v) = sp.edit().putBoolean("wifiOnly", v).apply()

    var lastSync: Long
        get() = sp.getLong("lastSync", 0L)
        set(v) = sp.edit().putLong("lastSync", v).apply()

    var lastResult: String?
        get() = sp.getString("lastResult", null)
        set(v) = sp.edit().putString("lastResult", v).apply()

    var lastOk: Boolean
        get() = sp.getBoolean("lastOk", true)
        set(v) = sp.edit().putBoolean("lastOk", v).apply()

    val isConfigured: Boolean get() = !cookie.isNullOrBlank() && !brandId.isNullOrBlank()

    val logLines: List<String>
        get() = sp.getString("log", "").orEmpty().lines().filter { it.isNotBlank() }

    @Synchronized
    fun log(msg: String) {
        val ts = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date())
        val lines = (listOf("$ts  $msg") + logLines).take(60)
        sp.edit().putString("log", lines.joinToString("\n")).apply()
    }

    fun clearLog() = sp.edit().remove("log").apply()

    fun logout() {
        sp.edit()
            .remove("cookie").remove("mainName").remove("brandId").remove("brandName")
            .apply()
    }
}
