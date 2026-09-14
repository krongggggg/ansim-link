package org.ansim.link

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class AlertWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val store = SessionStore(applicationContext)
        if (store.revokePending) RevokeWorker.enqueue(applicationContext)
        if (!store.monitoringEnabled || store.token.isEmpty()) return Result.success()
        return try {
            val state = ApiClient(store).request("GET", "/api/state")
            val me = state.optJSONObject("me") ?: throw ApiException(0, "서버 응답을 읽지 못했습니다.")
            if (me.optString("role") != "guardian") {
                store.monitoringEnabled = false
                return Result.success()
            }
            if (store.monitoringEnabled && store.token.isNotEmpty() && me.optString("id") == store.userId) {
                if (!me.optBoolean("sharing") && store.sharingEnabled) {
                    store.sharingEnabled = false
                    LocationTransmission.cancel()
                    applicationContext.stopService(android.content.Intent(applicationContext, SafetyService::class.java))
                }
                SafetyNotifications.consume(applicationContext, store, state)
            }
            Result.success()
        } catch (error: CancellationException) { throw error }
        catch (error: ApiException) {
            if (error.status == 401 && store.sharingEnabled) SafetyService.stop(applicationContext)
            if (error.status == 401 || error.status == 403) Result.success() else Result.retry()
        }
    }

    companion object {
        private const val NAME = "guardian-alert-monitoring"
        fun enqueue(context: Context) {
            val store = SessionStore(context)
            if (store.revokePending) RevokeWorker.enqueue(context)
            if (!store.monitoringEnabled) return
            SafetyNotifications.channels(context)
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
    }
}

class RevokeWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val store = SessionStore(applicationContext)
        if (!store.revokePending) {
            SafetyNotifications.revokeStatus(applicationContext, false)
            return Result.success()
        }
        return try {
            ApiClient(store).revokeSharing()
            SafetyNotifications.revokeStatus(applicationContext, store.revokePending)
            Result.success()
        } catch (error: CancellationException) { throw error }
        catch (error: ApiException) {
            SafetyNotifications.revokeStatus(applicationContext, true, error.status == 401 || error.status == 403)
            // Even expired credentials remain pending: no unsupported claim that the server erased data.
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "durable-sharing-revocation"
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RevokeWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
            SafetyNotifications.revokeStatus(context, true)
        }
    }
}
