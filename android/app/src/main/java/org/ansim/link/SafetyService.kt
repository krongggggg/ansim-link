package org.ansim.link

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import org.json.JSONObject

class SafetyService : Service(), LocationListener, SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var store: SessionStore
    private lateinit var manager: LocationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var latest: Location? = null
    private var started = false
    private var identity = ""
    private var credential = ""
    private var sentFixNanos = 0L
    private var sentWallTime = 0L

    override fun onCreate() {
        super.onCreate()
        store = SessionStore(this)
        manager = getSystemService(LocationManager::class.java)
        store.preferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop(this)
            return START_NOT_STICKY
        }
        // A null restart intent can never silently resume collection after process death.
        if (intent?.action != ACTION_START || !store.sharingEnabled || store.revokePending ||
            store.token.isEmpty() || !hasLocationPermission(this)) {
            if (store.sharingEnabled) store.markRevokePending()
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        try {
            val notification = SafetyNotifications.sharing(this)
            if (Build.VERSION.SDK_INT >= 29) startForeground(SafetyNotifications.SHARING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(SafetyNotifications.SHARING_ID, notification)
            identity = store.userId
            credential = store.token
            started = true
            running = true
            var registered = false
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (manager.allProviders.contains(provider) && manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(provider, 15_000L, 0f, this, Looper.getMainLooper())
                    registered = true
                }
            }
            if (!registered) {
                stop(this)
                return START_NOT_STICKY
            }
            scope.launch { collectAndPoll() }
        } catch (_: SecurityException) { stop(this) }
        catch (_: IllegalStateException) { stop(this) }
        return START_NOT_STICKY
    }

    private suspend fun collectAndPoll() {
        val api = ApiClient(store)
        while (scope.isActive) {
            if (!canCollect()) { handler.post { stop(this) }; return }
            val location = latest
            if (location != null && validFix(location) && location.elapsedRealtimeNanos > sentFixNanos && location.time > sentWallTime) {
                try {
                    api.request("POST", "/api/locations", JSONObject()
                        .put("latitude", location.latitude).put("longitude", location.longitude)
                        .put("accuracy", location.accuracy.toDouble()).put("recordedAt", Instant.ofEpochMilli(location.time).toString()))
                    sentFixNanos = location.elapsedRealtimeNanos
                    sentWallTime = location.time
                } catch (error: CancellationException) { throw error }
                catch (error: ApiException) {
                    if (error.status == 401 || error.status == 403 || !canCollect()) {
                        handler.post { stop(this) }; return
                    }
                }
            }
            if (!canCollect()) { handler.post { stop(this) }; return }
            try {
                val state = api.request("GET", "/api/state")
                if (!canCollect()) return
                val me = state.optJSONObject("me") ?: throw ApiException(0, "서버 응답을 읽지 못했습니다.")
                if (me.optString("id") != identity) throw ApiException(401, "이 기기의 프로필 연결 정보를 확인해 주세요.")
                if (!me.optBoolean("sharing")) {
                    store.sharingEnabled = false
                    LocationTransmission.cancel()
                    handler.post { stopSelf() }
                    return
                }
                SafetyNotifications.consume(this, store, state)
            } catch (error: CancellationException) { throw error }
            catch (error: ApiException) {
                if (error.status == 401 || error.status == 403) {
                    handler.post { stop(this) }; return
                }
            }
            // One bounded request at a time; keep only the newest fix, never a disk location queue.
            delay(15_000L)
        }
    }

    private fun canCollect(): Boolean = store.sharingEnabled && !store.revokePending &&
        store.userId == identity && store.token.isNotEmpty() && store.token == credential && hasLocationPermission(this)

    private fun validFix(location: Location): Boolean {
        val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        return location.hasAccuracy() && location.accuracy.isFinite() && location.accuracy >= 0f &&
            location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
            location.longitude.isFinite() && location.longitude in -180.0..180.0 &&
            age in 0L..120_000_000_000L && location.time in (System.currentTimeMillis() - 120_000L)..(System.currentTimeMillis() + 30_000L)
    }

    override fun onLocationChanged(location: Location) {
        if (!canCollect()) { stop(this); return }
        if (validFix(location) && location.elapsedRealtimeNanos > (latest?.elapsedRealtimeNanos ?: 0L)) latest = Location(location)
    }

    override fun onProviderDisabled(provider: String) {
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER) && !manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) stop(this)
    }
    override fun onProviderEnabled(provider: String) = Unit
    @Deprecated("Required on older Android releases")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (started && !canCollect()) {
            latest = null
            LocationTransmission.cancel()
            handler.post { stopSelf() }
        }
    }

    override fun onDestroy() {
        running = false
        store.preferences.unregisterOnSharedPreferenceChangeListener(this)
        manager.removeUpdates(this)
        latest = null
        LocationTransmission.cancel()
        scope.cancel()
        // Update/process teardown is not withdrawal of the saved sharing consent.
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile var running = false
            private set
        internal const val ACTION_STOP = "org.ansim.link.STOP_SHARING"
        private const val ACTION_START = "org.ansim.link.START_SHARING"
        internal fun hasLocationPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context) {
            val store = SessionStore(context)
            check(store.sharingEnabled && !store.revokePending && store.token.isNotEmpty()) { "서버 공유 상태를 먼저 확인해 주세요." }
            check(hasLocationPermission(context)) { "위치 권한을 먼저 허용해 주세요." }
            try { context.startForegroundService(Intent(context, SafetyService::class.java).setAction(ACTION_START)) }
            catch (error: RuntimeException) {
                store.markRevokePending()
                throw IllegalStateException("앱 화면을 연 상태에서 위치 공유를 다시 시작해 주세요.")
            }
        }

        fun stop(context: Context) {
            val store = SessionStore(context)
            store.markRevokePending()
            LocationTransmission.cancel()
            context.stopService(Intent(context, SafetyService::class.java))
        }
    }
}
