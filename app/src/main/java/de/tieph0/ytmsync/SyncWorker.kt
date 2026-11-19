package de.tieph0.ytmsync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_MSG = "msg"
    }

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.isConfigured) return Result.success()

        return try {
            setProgress(workDataOf(KEY_MSG to "Starte …"))
            val summary = withContext(Dispatchers.IO) {
                SyncEngine(applicationContext, { isStopped }) { msg ->
                    setProgress(workDataOf(KEY_MSG to msg))
                }.run()
            }
            prefs.lastSync = System.currentTimeMillis()
            prefs.lastResult = summary
            prefs.lastOk = true
            prefs.log("✓ $summary")
            Result.success()
        } catch (e: AuthException) {
            fail(prefs, e.message ?: "Login abgelaufen")
            Notifier.loginExpired(applicationContext)
            Result.failure()
        } catch (e: IOException) {
            fail(prefs, "Netzwerk: ${e.message}")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        } catch (e: Exception) {
            fail(prefs, e.message ?: e.javaClass.simpleName)
            Result.failure()
        }
    }

    private fun fail(prefs: Prefs, msg: String) {
        prefs.lastSync = System.currentTimeMillis()
        prefs.lastResult = msg
        prefs.lastOk = false
        prefs.log("✗ $msg")
    }
}
