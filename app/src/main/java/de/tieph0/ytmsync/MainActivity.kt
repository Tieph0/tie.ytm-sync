package de.tieph0.ytmsync

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Scheduler.apply(this)
        setContent { AppTheme { MainScreen() } }
    }
}

private data class UiState(
    val loggedIn: Boolean,
    val configured: Boolean,
    val mainName: String?,
    val brandName: String?,
    val autoSync: Boolean,
    val syncPlaylists: Boolean,
    val wifiOnly: Boolean,
    val lastSync: Long,
    val lastResult: String?,
    val lastOk: Boolean,
    val log: List<String>,
)

private fun Prefs.snapshot() = UiState(
    loggedIn = !cookie.isNullOrBlank(),
    configured = isConfigured,
    mainName = mainName,
    brandName = brandName,
    autoSync = autoSync,
    syncPlaylists = syncPlaylists,
    wifiOnly = wifiOnly,
    lastSync = lastSync,
    lastResult = lastResult,
    lastOk = lastOk,
    log = logLines,
)

@SuppressLint("BatteryLife")
@Composable
private fun MainScreen() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()

    // --- observe preferences
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick++ }
        prefs.sp.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val ui = remember(tick) { prefs.snapshot() }

    // --- observe work
    val wm = remember { WorkManager.getInstance(ctx) }
    val onceFlow = remember { wm.getWorkInfosForUniqueWorkFlow(Scheduler.ONCE) }
    val periodicFlow = remember { wm.getWorkInfosForUniqueWorkFlow(Scheduler.PERIODIC) }
    val once by onceFlow.collectAsState(initial = emptyList())
    val periodic by periodicFlow.collectAsState(initial = emptyList())
    val running = (once + periodic).firstOrNull { it.state == WorkInfo.State.RUNNING }
    val queuedNow = once.any { it.state == WorkInfo.State.ENQUEUED }
    val progressMsg = running?.progress?.getString(SyncWorker.KEY_MSG)
    val nextRun = periodic.firstOrNull { it.state == WorkInfo.State.ENQUEUED }?.nextScheduleTimeMillis

    // --- dialogs / setup
    var showPicker by remember { mutableStateOf(false) }
    var showCookie by remember { mutableStateOf(false) }
    var channels by remember { mutableStateOf<List<Channel>?>(null) }
    var pickerError by remember { mutableStateOf<String?>(null) }
    var detectedMain by remember { mutableStateOf<String?>(null) }

    fun loadChannels() {
        showPicker = true
        channels = null
        pickerError = null
        scope.launch {
            val cookie = prefs.cookie ?: return@launch
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val api = YtmClient(cookie)
                    detectedMain = runCatching { api.accountName(null) }.getOrNull()
                    api.listChannels()
                }
            }
            result.onSuccess { channels = it }
                .onFailure { channels = emptyList(); pickerError = it.message }
        }
    }

    val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        tick++
        if (it.resultCode == android.app.Activity.RESULT_OK) loadChannels()
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val pm = ctx.getSystemService(PowerManager::class.java)
    val batteryFree = remember(tick) { pm.isIgnoringBatteryOptimizations(ctx.packageName) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = pad.calculateTopPadding() + 16.dp,
                bottom = pad.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Header() }

            if (!ui.loggedIn) {
                item {
                    SetupCard(
                        onLogin = { loginLauncher.launch(Intent(ctx, LoginActivity::class.java)) },
                        onCookie = { showCookie = true },
                    )
                }
            } else if (!ui.configured) {
                item {
                    SectionCard("Fast fertig") {
                        Text("Wähle jetzt deinen Brand-Kanal (z. B. Tieph0) aus.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { loadChannels() }) { Text("Kanal auswählen") }
                    }
                }
            } else {
                item {
                    StatusCard(
                        ui = ui,
                        running = running != null,
                        queued = queuedNow,
                        progressMsg = progressMsg,
                        nextRun = nextRun,
                        onSync = { Scheduler.runNow(ctx) },
                    )
                }
                item {
                    SectionCard("Kanäle") {
                        ChannelRow("Hauptkanal", ui.mainName ?: "wird beim ersten Sync erkannt")
                        Text("⇅", modifier = Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.secondary, fontSize = 18.sp)
                        ChannelRow("Brand-Kanal", ui.brandName ?: "–")
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { loadChannels() }) { Text("Kanal ändern") }
                            OutlinedButton(onClick = {
                                prefs.logout()
                                Scheduler.apply(ctx)
                            }) { Text("Abmelden") }
                        }
                    }
                }
                item {
                    SectionCard("Einstellungen") {
                        ToggleRow("Automatisch stündlich", "Läuft im Hintergrund, auch nach Neustart", ui.autoSync) {
                            prefs.autoSync = it; Scheduler.apply(ctx)
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                        ToggleRow("Playlists synchronisieren", "Eigene Playlists nach Titel abgleichen", ui.syncPlaylists) {
                            prefs.syncPlaylists = it
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                        ToggleRow("Nur im WLAN", "Spart mobile Daten", ui.wifiOnly) {
                            prefs.wifiOnly = it; Scheduler.apply(ctx)
                        }
                    }
                }
                if (!batteryFree) {
                    item {
                        SectionCard("Akku-Optimierung") {
                            Text(
                                "Manche Hersteller beenden Hintergrund-Jobs aggressiv. Wenn Syncs ausbleiben, " +
                                    "nimm YTM Sync von der Akku-Optimierung aus. Der Verbrauch bleibt minimal – " +
                                    "die App läuft nur ein paar Sekunden pro Stunde.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = {
                                settingsLauncher.launch(
                                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                                )
                            }) { Text("Ausnahme erlauben") }
                        }
                    }
                }
            }

            if (ui.log.isNotEmpty()) {
                item {
                    SectionCard("Verlauf", action = { TextButton(onClick = { prefs.clearLog() }) { Text("Leeren") } }) {
                        ui.log.take(25).forEach {
                            Text(
                                it,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = if (it.contains("✗")) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPicker) {
        ChannelPicker(
            channels = channels,
            error = pickerError,
            detectedMain = detectedMain,
            onDismiss = { showPicker = false },
            onPick = { id, name ->
                prefs.brandId = id
                prefs.brandName = name
                detectedMain?.let { prefs.mainName = it }
                showPicker = false
                Scheduler.apply(ctx)
                Scheduler.runNow(ctx)
            },
        )
    }

    if (showCookie) {
        CookieDialog(
            onDismiss = { showCookie = false },
            onSave = { c ->
                prefs.cookie = c
                showCookie = false
                loadChannels()
            },
        )
    }
}

// ---------------------------------------------------------------- components

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(AccentBrush),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Refresh, null, tint = Color.White) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("YTM Sync", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Hauptkanal ⇄ Brand-Kanal", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SetupCard(onLogin: () -> Unit, onCookie: () -> Unit) {
    SectionCard("Einrichtung") {
        Text(
            "Melde dich mit dem Google-Konto an, unter dem beide Kanäle liegen. " +
                "Danach wählst du deinen Brand-Kanal aus – Likes und Playlists werden dann stündlich in beide Richtungen abgeglichen.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("Mit Google anmelden") }
        TextButton(onClick = onCookie, modifier = Modifier.fillMaxWidth()) { Text("Cookie manuell einfügen") }
    }
}

@Composable
private fun StatusCard(
    ui: UiState,
    running: Boolean,
    queued: Boolean,
    progressMsg: String?,
    nextRun: Long?,
    onSync: () -> Unit,
) {
    val busy = running || queued
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(AccentBrush).padding(22.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        busy -> CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 3.dp)
                        ui.lastSync == 0L -> Icon(Icons.Filled.Refresh, null, tint = Color.White)
                        ui.lastOk -> Icon(Icons.Filled.CheckCircle, null, tint = Color.White)
                        else -> Icon(Icons.Filled.Warning, null, tint = Color.White)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    val title = when {
                        running -> "Synchronisiere …"
                        queued -> "Wartet auf Netz …"
                        ui.lastSync == 0L -> "Noch nicht synchronisiert"
                        ui.lastOk -> "Synchron"
                        else -> "Problem beim letzten Sync"
                    }
                    Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    if (ui.lastSync > 0) {
                        Text(
                            "Zuletzt " + DateUtils.getRelativeTimeSpanString(ui.lastSync).toString().lowercase(),
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            val detail = if (running) progressMsg ?: "…" else ui.lastResult
            if (detail != null) {
                Text(detail, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
            if (running) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().clip(CircleShape),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.25f),
                )
            }
            if (ui.autoSync && nextRun != null && nextRun > System.currentTimeMillis() && !running) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Nächster Auto-Sync " + DateUtils.getRelativeTimeSpanString(nextRun).toString().lowercase(),
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onSync,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Violet,
                    disabledContainerColor = Color.White.copy(alpha = 0.35f),
                    disabledContentColor = Color.White,
                ),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Icon(Icons.Filled.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text("Jetzt synchronisieren", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                action?.invoke()
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun ChannelRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Icon(Icons.Filled.AccountCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChannelPicker(
    channels: List<Channel>?,
    error: String?,
    detectedMain: String?,
    onDismiss: () -> Unit,
    onPick: (id: String, name: String) -> Unit,
) {
    var manualId by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Brand-Kanal wählen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (detectedMain != null) {
                    Text("Hauptkanal: $detectedMain", color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(10.dp))
                }
                when {
                    channels == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Lade Kanäle …")
                    }
                    else -> {
                        val brands = channels.filter { it.pageId != null }
                        if (brands.isEmpty()) {
                            Text(
                                (error?.let { "Fehler: $it\n\n" } ?: "") +
                                    "Keine Brand-Kanäle automatisch gefunden. Trag die ID manuell ein: " +
                                    "myaccount.google.com/brandaccounts → Kanal antippen → die lange Zahl in der URL.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        brands.forEach { ch ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onPick(ch.pageId!!, listOfNotNull(ch.name, ch.handle).joinToString(" ")) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                            ) {
                                Icon(Icons.Filled.AccountCircle, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(ch.name, style = MaterialTheme.typography.bodyLarge)
                                    ch.handle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = manualId,
                            onValueChange = { manualId = it.filter(Char::isDigit) },
                            label = { Text("Oder ID manuell") },
                            singleLine = true,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = manualId.length >= 15,
                onClick = { onPick(manualId, "Brand ($manualId)") },
            ) { Text("Übernehmen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun CookieDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val valid = YtmClient.cookieLooksValid(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cookie einfügen") },
        text = {
            Column {
                Text(
                    "Am PC auf music.youtube.com eingeloggt: F12 → Netzwerk → eine /browse-Anfrage → " +
                        "Request-Header \"Cookie\" komplett kopieren.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = text, onValueChange = { text = it.trim() }, label = { Text("Cookie") }, maxLines = 4)
                if (text.isNotEmpty() && !valid) {
                    Text("Kein SAPISID im Cookie gefunden", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(text) }) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
