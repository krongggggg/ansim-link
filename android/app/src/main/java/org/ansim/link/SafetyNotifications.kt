package org.ansim.link

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal object SafetyNotifications {
    private val mutex = Mutex()
    const val SHARING_ID = 1
    private const val REVOKE_ID = 2

    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("sharing", "위치 공유 상태", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("safety", "가족 안전 알림", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun open(context: Context): PendingIntent = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun sharing(context: Context): Notification {
        channels(context)
        val stop = PendingIntent.getService(context, 1,
            Intent(context, SafetyService::class.java).setAction(SafetyService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context, "sharing")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("어딧 · 위치 공유 중")
            .setContentText("가족에게 위치를 보내고 있습니다. 중지하면 기기 수집이 즉시 멈춥니다.")
            .setContentIntent(open(context)).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "공유 중지", stop).build()).build()
    }

    suspend fun consume(context: Context, store: SessionStore, state: JSONObject) = mutex.withLock {
        if (store.token.isEmpty() || state.optJSONObject("me")?.optString("id") != store.userId) return@withLock
        val events = state.optJSONArray("events") ?: return@withLock
        val ordered = (0 until events.length()).mapNotNull { events.optJSONObject(it) }.sortedBy { it.optLong("id") }
        val newest = ordered.maxOfOrNull { it.optLong("id") } ?: 0L
        if (!store.eventsInitialized) {
            store.lastEventId = maxOf(store.lastEventId, newest)
            store.eventsInitialized = true
            return@withLock
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        channels(context)
        for (event in ordered) {
            val id = event.optLong("id")
            if (id <= store.lastEventId) continue
            if (canNotify(context)) {
                val notification = Notification.Builder(context, "safety")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(event.optString("title", "가족 안전 알림").take(120))
                    .setContentText(event.optString("body").take(300))
                    .setStyle(Notification.BigTextStyle().bigText(event.optString("body").take(600)))
                    .setContentIntent(open(context)).setAutoCancel(true).setOnlyAlertOnce(true)
                    .setVisibility(Notification.VISIBILITY_PRIVATE).build()
                // A stable tag also prevents duplicate tray entries after a process interruption.
                manager.notify("event:$id", 0, notification)
            }
            store.lastEventId = id
        }
    }

    fun revokeStatus(context: Context, pending: Boolean, authenticationRequired: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!pending) { manager.cancel(REVOKE_ID); return }
        channels(context)
        if (!canNotify(context)) return
        manager.notify(REVOKE_ID, Notification.Builder(context, "safety")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("기기 수집 중지 · 서버 확인 대기")
            .setContentText(if (authenticationRequired) "서버 공유 중지를 확인할 수 없습니다. 서버 운영자에게 기존 프로필의 공유 중지를 요청해 주세요."
                else "기존 서버 위치 삭제를 재시도합니다. 인터넷 연결을 유지해 주세요.")
            .setContentIntent(open(context)).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).build())
    }

    fun clear(context: Context) { context.getSystemService(NotificationManager::class.java).cancelAll() }

    private fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
}
