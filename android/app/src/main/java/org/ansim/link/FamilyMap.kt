package org.ansim.link

import android.content.ComponentCallbacks
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.os.BundleCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.naver.maps.geometry.LatLng
import com.naver.maps.geometry.LatLngBounds
import com.naver.maps.map.CameraPosition
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.MapView
import com.naver.maps.map.NaverMap
import com.naver.maps.map.NaverMapOptions
import com.naver.maps.map.NaverMapSdk
import com.naver.maps.map.overlay.CircleOverlay
import com.naver.maps.map.overlay.InfoWindow
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.Overlay
import com.naver.maps.map.overlay.PolylineOverlay
import org.json.JSONObject
import java.util.UUID

internal class FamilyMapState {
    internal var camera = CameraPosition(LatLng(37.5665, 126.9780), 14.0)
    internal var focusInitialized = false
    internal var requestedId: String? = null
    internal var requestedRecenter = 0
    internal var requestedHistory = false
    internal var focusPending = true
    internal var owner: Any? = null
    internal var currentCamera: (() -> CameraPosition)? = null

    internal companion object {
        val StateSaver = Saver<FamilyMapState, Bundle>(
            save = { state ->
                Bundle().apply {
                    putParcelable("camera", state.currentCamera?.invoke() ?: state.camera)
                    putBoolean("initialized", state.focusInitialized)
                    putString("selected", state.requestedId)
                    putInt("recenter", state.requestedRecenter)
                    putBoolean("history", state.requestedHistory)
                    putBoolean("pending", state.focusPending)
                }
            },
            restore = { saved ->
                FamilyMapState().apply {
                    camera = BundleCompat.getParcelable(saved, "camera", CameraPosition::class.java) ?: camera
                    focusInitialized = saved.getBoolean("initialized")
                    requestedId = saved.getString("selected")
                    requestedRecenter = saved.getInt("recenter")
                    requestedHistory = saved.getBoolean("history")
                    focusPending = saved.getBoolean("pending")
                }
            }
        )
    }
}

@Composable
internal fun rememberFamilyMapState(): FamilyMapState =
    rememberSaveable(saver = FamilyMapState.StateSaver) { FamilyMapState() }

// The SDK has one process-wide callback. A map leaving composition must not
// remove another map's callback or restore a callback belonging to a dead view.
private object FamilyMapAuthCallbacks {
    private val listeners = linkedSetOf<NaverMapSdk.OnAuthFailedListener>()
    private var previous: NaverMapSdk.OnAuthFailedListener? = null
    private val dispatcher = NaverMapSdk.OnAuthFailedListener { error ->
        val recipients = synchronized(this) { listeners.toList() to previous }
        recipients.first.forEach { it.onAuthFailed(error) }
        recipients.second?.onAuthFailed(error)
    }

    @Synchronized
    fun attach(sdk: NaverMapSdk, listener: NaverMapSdk.OnAuthFailedListener) {
        if (listeners.isEmpty()) {
            previous = sdk.onAuthFailedListener
            sdk.onAuthFailedListener = dispatcher
        }
        listeners.add(listener)
    }

    @Synchronized
    fun detach(sdk: NaverMapSdk, listener: NaverMapSdk.OnAuthFailedListener) {
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            if (sdk.onAuthFailedListener === dispatcher) sdk.onAuthFailedListener = previous
            previous = null
        }
    }
}

private data class MapPerson(val id: String, val name: String, val coordinate: LatLng, val details: String)
private data class MapZone(val coordinate: LatLng, val radius: Double, val details: String)

private fun JSONObject.mapCoordinate(): LatLng? {
    val latitude = optDouble("latitude")
    val longitude = optDouble("longitude")
    return if (latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) {
        LatLng(latitude, longitude)
    } else null
}

@Composable
internal fun FamilyMap(
    state: JSONObject?,
    history: List<JSONObject> = emptyList(),
    modifier: Modifier = Modifier,
    mapState: FamilyMapState = rememberFamilyMapState(),
    selectedId: String? = null,
    recenterRequest: Int = 0,
    onSelect: (String) -> Unit = {},
    bottomPadding: Dp = 0.dp,
    historyMode: Boolean = false,
    topPadding: Dp = 0.dp
) {
    if (!BuildConfig.NAVER_MAP_CONFIGURED) {
        Box(modifier.padding(top = topPadding, bottom = bottomPadding), contentAlignment = Alignment.Center) {
            SafetyCard {
                Icon(Icons.Rounded.Map, null, tint = Violet, modifier = Modifier.size(32.dp))
                Text("네이버 지도 설정 필요", fontWeight = FontWeight.Bold)
                Text("Maps Key ID가 설정되지 않아 지도를 표시할 수 없습니다. 앱 운영자가 org.ansim.link를 등록하고 지도 키를 설정해야 합니다.", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
            }
        }
        return
    }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val registry = LocalSavedStateRegistryOwner.current.savedStateRegistry
    val density = LocalDensity.current
    val bottomPixels = with(density) { bottomPadding.roundToPx().coerceAtLeast(0) }
    val topPixels = with(density) { topPadding.roundToPx().coerceAtLeast(0) }
    val routeInset = with(density) { 40.dp.roundToPx() }
    val savedStateKey = rememberSaveable { "naver-map-${UUID.randomUUID()}" }
    val view = remember(context, savedStateKey, mapState) {
        MapView(context, NaverMapOptions().useTextureView(true)
            .camera(mapState.currentCamera?.invoke() ?: mapState.camera)
            .locationButtonEnabled(false).logoClickEnabled(true)).apply {
            onCreate(registry.consumeRestoredStateForKey(savedStateKey))
        }
    }
    var controller by remember(view) { mutableStateOf<NaverMap?>(null) }
    var authError by remember(view) { mutableStateOf<String?>(null) }
    val selectPerson by rememberUpdatedState(onSelect)
    val markers = remember(view) { mutableMapOf<String, Marker>() }
    val circles = remember(view) { mutableMapOf<String, CircleOverlay>() }
    val path = remember(view) {
        PolylineOverlay().apply {
            color = Violet.toArgb()
            width = (3 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        }
    }
    val historyPoint = remember(view) { Marker().apply { captionText = "이동 기록" } }
    val information = remember(view) {
        InfoWindow().apply {
            adapter = object : InfoWindow.DefaultTextAdapter(context) {
                override fun getText(infoWindow: InfoWindow): CharSequence {
                    val value = infoWindow.marker?.tag ?: infoWindow.tag
                    return when (value) {
                        is MapPerson -> value.details
                        is CircleOverlay -> value.tag as? String ?: ""
                        is String -> value
                        else -> ""
                    }
                }
            }
        }
    }
    val markerClick = remember(view, information, mapState) {
        Overlay.OnClickListener { overlay ->
            val marker = overlay as Marker
            val person = marker.tag as? MapPerson
            if (person != null) {
                controller?.moveCamera(CameraUpdate.scrollAndZoomTo(marker.position, 16.0))
                mapState.focusPending = false
                selectPerson(person.id)
            }
            information.tag = null
            if (information.marker === marker) information.close() else information.open(marker)
            true
        }
    }
    DisposableEffect(view, lifecycle, registry, mapState) {
        var started = false
        var resumed = false
        var destroyed = false
        var nativeMap: NaverMap? = null
        mapState.owner = view
        val sdk = NaverMapSdk.getInstance(context)
        val cameraChanged = NaverMap.OnCameraChangeListener { reason, _ ->
            if (mapState.owner === view && (reason == CameraUpdate.REASON_GESTURE || reason == CameraUpdate.REASON_CONTROL)) {
                // A gesture also cancels a pending first fix: polling must never undo it.
                mapState.focusPending = false
            }
        }
        val cameraIdle = NaverMap.OnCameraIdleListener {
            if (mapState.owner === view) nativeMap?.let { mapState.camera = it.cameraPosition }
        }
        val authListener = NaverMapSdk.OnAuthFailedListener { error ->
            view.post {
                if (!destroyed) authError = when (error.errorCode) {
                    "401" -> "네이버 지도 인증 실패 · Key ID와 Android 패키지 등록을 확인해 주세요."
                    "429" -> "네이버 지도 사용 불가 · Dynamic Map 활성화와 사용 한도를 확인해 주세요."
                    else -> "네이버 지도 인증 오류 (${error.errorCode}) · 앱 운영자에게 문의해 주세요."
                }
            }
        }
        fun releaseMap() {
            if (destroyed) return
            destroyed = true
            nativeMap?.let { map ->
                if (mapState.owner === view) mapState.camera = map.cameraPosition
                map.removeOnCameraChangeListener(cameraChanged)
                map.removeOnCameraIdleListener(cameraIdle)
                map.setOnMapClickListener(null)
                map.cancelTransitions()
            }
            if (mapState.owner === view) {
                mapState.currentCamera = null
                mapState.owner = null
            }
            information.close()
            information.tag = null
            information.adapter = InfoWindow.DEFAULT_ADAPTER
            markers.values.forEach { it.map = null; it.onClickListener = null; it.tag = null }
            markers.clear()
            circles.values.forEach { it.map = null; it.onClickListener = null; it.tag = null }
            circles.clear()
            path.map = null
            historyPoint.map = null
            historyPoint.onClickListener = null
            historyPoint.tag = null
            nativeMap = null
            controller = null
            FamilyMapAuthCallbacks.detach(sdk, authListener)
            if (resumed) { view.onPause(); resumed = false }
            if (started) { view.onStop(); started = false }
            view.onDestroy()
        }
        val observer = LifecycleEventObserver { _, event ->
            if (!destroyed) when (event) {
                Lifecycle.Event.ON_START -> if (!started) { view.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> if (!resumed) { view.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { view.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> if (started) { view.onStop(); started = false }
                Lifecycle.Event.ON_DESTROY -> releaseMap()
                else -> Unit
            }
        }
        val memoryCallbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = Unit
            override fun onLowMemory() { if (!destroyed) view.onLowMemory() }
        }
        registry.registerSavedStateProvider(savedStateKey) {
            Bundle().also { if (!destroyed) view.onSaveInstanceState(it) }
        }
        context.registerComponentCallbacks(memoryCallbacks)
        FamilyMapAuthCallbacks.attach(sdk, authListener)
        lifecycle.addObserver(observer)
        view.getMapAsync { map ->
            if (!destroyed) {
                nativeMap = map
                map.uiSettings.apply {
                    isLocationButtonEnabled = false
                    isLogoClickEnabled = true // Retain the SDK's legal attribution dialog.
                    isScaleBarEnabled = true
                    isZoomControlEnabled = true
                }
                map.setContentPadding(0, topPixels, 0, bottomPixels, true)
                map.moveCamera(CameraUpdate.toCameraPosition(mapState.camera))
                map.addOnCameraChangeListener(cameraChanged)
                map.addOnCameraIdleListener(cameraIdle)
                map.setOnMapClickListener { _, _ -> information.close() }
                if (mapState.owner === view) mapState.currentCamera = { map.cameraPosition }
                controller = map
            }
        }
        onDispose {
            lifecycle.removeObserver(observer)
            registry.unregisterSavedStateProvider(savedStateKey)
            context.unregisterComponentCallbacks(memoryCallbacks)
            releaseMap()
        }
    }
    val points = remember(state, historyMode) {
        buildMap<String, MapPerson> {
            if (!historyMode) {
                fun add(person: JSONObject?, point: JSONObject?) {
                    if (person?.optBoolean("sharing") != true || point == null) return
                    val id = person.optString("id")
                    val coordinate = point.mapCoordinate() ?: return
                    if (id.isEmpty()) return
                    val name = person.optString("name")
                    put(id, MapPerson(id, name, coordinate, "$name\n마지막 위치 ${whenText(point.optString("recordedAt"))}"))
                }
                add(state?.optJSONObject("me"), state?.optJSONObject("location"))
                state?.array("members")?.forEach { add(it, it.optJSONObject("location")) }
            }
        }
    }
    val zones = remember(state, historyMode) {
        buildMap<String, MapZone> {
            if (!historyMode) state?.array("zones")?.forEach { zone ->
                val coordinate = zone.mapCoordinate() ?: return@forEach
                val radius = zone.optDouble("radius")
                val id = zone.optString("id")
                if (id.isNotEmpty() && radius.isFinite() && radius > 0) {
                    put(id, MapZone(coordinate, radius, "${zone.optString("name")} · 반경 ${zone.optInt("radius")}m"))
                }
            }
        }
    }
    val route = remember(history, historyMode) {
        if (historyMode) history.mapNotNull { it.mapCoordinate() } else emptyList()
    }
    val historyDetails = remember(history, historyMode) {
        if (historyMode) "이동 기록\n${whenText(history.firstOrNull { it.mapCoordinate() != null }?.optString("recordedAt").orEmpty())}" else ""
    }
    val routeBounds = remember(route) { if (route.size >= 2) LatLngBounds.from(route) else null }
    var renderedRoute by remember(view) { mutableStateOf<List<LatLng>?>(null) }
    var appliedBottom by remember(view) { mutableStateOf<Int?>(null) }
    var appliedTop by remember(view) { mutableStateOf<Int?>(null) }
    var shownSelection by remember(view) { mutableStateOf<String?>(null) }
    // Observe SDK readiness during composition, not just inside the commit callback.
    val readyMap = controller
    SideEffect {
        val map = readyMap
        if (map != null) {
            if (appliedBottom != bottomPixels || appliedTop != topPixels) {
                map.setContentPadding(0, topPixels, 0, bottomPixels, true)
                appliedBottom = bottomPixels
                appliedTop = topPixels
            }
            if (shownSelection != selectedId) {
                if ((information.marker?.tag as? MapPerson)?.id != selectedId) information.close()
                shownSelection = selectedId
            }
            val markerIterator = markers.iterator()
            while (markerIterator.hasNext()) {
                val (id, marker) = markerIterator.next()
                if (id !in points) {
                    if (information.marker === marker) information.close()
                    marker.map = null
                    marker.onClickListener = null
                    marker.tag = null
                    markerIterator.remove()
                }
            }
            for ((id, person) in points) {
                val marker = markers.getOrPut(id) { Marker().apply { onClickListener = markerClick } }
                val selected = id == selectedId
                if (marker.position != person.coordinate) marker.position = person.coordinate
                marker.captionText = if (selected) "${person.name} · 선택됨" else person.name
                marker.iconTintColor = if (selected) Violet.toArgb() else Mint.toArgb()
                marker.captionColor = if (selected) Violet.toArgb() else Ink.toArgb()
                marker.captionTextSize = if (selected) 15f else 12f
                marker.zIndex = if (selected) 10 else 0
                marker.isForceShowIcon = selected
                marker.isForceShowCaption = selected
                if (marker.tag != person) {
                    marker.tag = person
                    if (information.marker === marker) information.invalidate()
                }
                if (marker.map !== map) marker.map = map
            }
            val circleIterator = circles.iterator()
            while (circleIterator.hasNext()) {
                val (id, circle) = circleIterator.next()
                if (id !in zones) {
                    if (information.tag === circle) { information.close(); information.tag = null }
                    circle.map = null
                    circle.onClickListener = null
                    circle.tag = null
                    circleIterator.remove()
                }
            }
            for ((id, zone) in zones) {
                val circle = circles.getOrPut(id) {
                    CircleOverlay().apply {
                        color = Violet.copy(alpha = 0.12f).toArgb()
                        outlineColor = Violet.toArgb()
                        outlineWidth = context.resources.displayMetrics.density.toInt().coerceAtLeast(1)
                        onClickListener = Overlay.OnClickListener {
                            information.close()
                            information.position = center
                            information.tag = this
                            information.open(map)
                            true
                        }
                    }
                }
                circle.center = zone.coordinate
                circle.radius = zone.radius
                circle.tag = zone.details
                if (information.tag === circle) {
                    information.position = circle.center
                    information.invalidate()
                }
                if (circle.map !== map) circle.map = map
            }
            if (renderedRoute != route) {
                if (information.marker === historyPoint) information.close()
                path.map = null
                historyPoint.map = null
                historyPoint.tag = null
                if (route.size >= 2) {
                    path.coords = route
                    path.map = map
                } else if (route.size == 1) {
                    historyPoint.position = route[0]
                    historyPoint.onClickListener = markerClick
                    historyPoint.map = map
                }
                renderedRoute = route
            }
            if (route.size == 1 && historyPoint.tag != historyDetails) {
                historyPoint.tag = historyDetails
                if (information.marker === historyPoint) information.invalidate()
            }
            if (!mapState.focusInitialized || mapState.requestedId != selectedId ||
                mapState.requestedRecenter != recenterRequest || mapState.requestedHistory != historyMode) {
                mapState.focusInitialized = true
                mapState.requestedId = selectedId
                mapState.requestedRecenter = recenterRequest
                mapState.requestedHistory = historyMode
                mapState.focusPending = true
            }
            if (mapState.focusPending) {
                if (historyMode && route.isNotEmpty()) {
                    mapState.focusPending = false
                    if (routeBounds != null && route.any { it != route[0] }) {
                        map.moveCamera(CameraUpdate.fitBounds(routeBounds, routeInset).finishCallback {
                            if (!map.isDestroyed && map.cameraPosition.zoom > 16.0) map.moveCamera(CameraUpdate.zoomTo(16.0))
                        })
                    } else map.moveCamera(CameraUpdate.scrollAndZoomTo(route[0], 16.0))
                } else if (!historyMode) {
                    // A requested person with sharing off/no fix must never fall back to someone else.
                    val target = if (selectedId != null) points[selectedId] else points.values.firstOrNull()
                    if (target != null) {
                        mapState.focusPending = false
                        map.moveCamera(CameraUpdate.scrollAndZoomTo(target.coordinate, 16.0))
                    }
                }
            }
        }
    }
    val emptyMessage = when {
        historyMode && route.isEmpty() -> "표시할 이동 기록이 없습니다"
        !historyMode && selectedId != null && selectedId !in points -> "선택한 가족의 공유된 위치가 없습니다"
        !historyMode && points.isEmpty() -> "공유된 위치가 없습니다"
        else -> null
    }
    Box(modifier) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        val message = authError ?: emptyMessage
        if (message != null) {
            Surface(Modifier.align(Alignment.TopCenter).padding(start = 12.dp, end = 12.dp, top = topPadding + 12.dp), shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = 0.97f)) {
                Text(message, Modifier.padding(12.dp), color = if (authError != null) Danger else Muted, fontSize = 12.sp, lineHeight = 19.sp)
            }
        }
    }
}
