package de.tieph0.ytmsync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Hourly sync via WorkManager: no permanent service, the system batches the
 * run with other jobs (15 min flex window) and only starts it with network
 * and battery not low. WorkManager re-registers the job after reboots itself.
 */
object Scheduler {
    const val PERIODIC = "ytm-sync-periodic"
    const val ONCE = "ytm-sync-now"

    private fun network(prefs: Prefs) =
        if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

    fun apply(context: Context) {
        val prefs = Prefs(context)
        val wm = WorkManager.getInstance(context)
        if (!prefs.autoSync || !prefs.isConfigured) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(network(prefs))
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS, 15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, request)
    }
}
