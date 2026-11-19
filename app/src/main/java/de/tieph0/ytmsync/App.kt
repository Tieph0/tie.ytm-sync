package de.tieph0.ytmsync

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannel(this)
    }
}

/** Re-applies the schedule after reboot / app update (WorkManager also does this itself). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.apply(context)
    }
}

object Notifier {
    private const val CHANNEL = "status"
    private const val ID_LOGIN = 1

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Sync-Status", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Nur bei Problemen, z. B. abgelaufenem Login"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun loginExpired(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle("YTM Sync pausiert")
            .setContentText("Login abgelaufen – tippe, um dich neu anzumelden.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_LOGIN, n)
        } catch (_: SecurityException) {
        }
    }
}
