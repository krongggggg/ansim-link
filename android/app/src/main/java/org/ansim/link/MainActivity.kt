package org.ansim.link

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal val Ink = Color(0xFF20243C)
internal val Muted = Color(0xFF72788E)
internal val Violet = Color(0xFF5355D9)
internal val Canvas = Color(0xFFF5F6FA)
internal val Mint = Color(0xFF107968)
internal val Danger = Color(0xFFCB4057)
private val palette = lightColorScheme(primary = Violet, secondary = Mint, background = Canvas, surface = Color.White, onSurface = Ink, onBackground = Ink, error = Danger)
internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.array(name: String) = optJSONArray(name)?.objects().orEmpty()
internal fun json(vararg fields: Pair<String, Any?>) = JSONObject().apply { fields.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
internal fun whenText(value: String): String = runCatching { DateTimeFormatter.ofPattern("MM.dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value)) }.getOrDefault("시간 정보 없음")

class MainActivity : ComponentActivity() {
    private lateinit var store: SessionStore
    private lateinit var api: ApiClient
    private var hasSession by mutableStateOf(false)
    private var state by mutableStateOf<JSONObject?>(null)
    private var busy by mutableStateOf(false)
    private var revokePending by mutableStateOf(false)
    private var pendingInvitation by mutableStateOf<IncomingInvitation?>(null)
    private var invitationError by mutableStateOf<String?>(null)
    private val snackbar = SnackbarHostState()
    private var permissionAction = ""
    private val invitationScanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::enterScannedInvitation)
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (!allowed) notice("알림이 차단되어 있습니다. 앱의 알림함에서 직접 확인하거나 시스템 설정에서 허용해 주세요.")
    }
    private val locationPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val action = permissionAction
        permissionAction = ""
        val allowed = result.values.any { it } || hasLocation()
        if (action == "share") {
            if (allowed) enableSharing() else notice("위치 권한이 없어 공유를 시작하지 않았습니다.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SessionStore(this)
        api = ApiClient(store)
        hasSession = store.token.isNotBlank()
        revokePending = store.revokePending
        pendingInvitation = store.pendingInvitation()
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) receiveInvitation(intent)
        AlertWorker.enqueue(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = palette) {
                Surface(Modifier.fillMaxSize(), color = Canvas) {
                    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = Canvas) { padding ->
                        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                            val invitation = pendingInvitation
                            if (!hasSession && invitation == null) {
                                InvitationLandingScreen(busy, store.baseUrl, ::scanInvitation, ::enterInvitation)
                            } else if (hasSession && invitation == null) HomeScreen(state, busy, revokePending, SafetyService.running,
                                onRefresh = { act { refresh() } },
                                onShare = ::changeSharing,
                                onMutation = { method, path, body -> act { api.request(method, path, body); refresh() } },
                                onInvite = { result -> act { result(api.request("POST", "/api/invites", json())); refresh() } },
                                onScanInvitation = ::scanInvitation,
                                onAcceptInvite = { code, accepted -> act {
                                    api.request("POST", "/api/invites/accept", json("code" to code))
                                    refresh()
                                    accepted()
                                    notice("가족과 연결했습니다. 위치 공유는 각자 동의하고 켜 주세요.")
                                } },
                                onHistory = { id ->
                                    try { api.request("GET", "/api/locations/$id").array("locations") }
                                    catch (e: ApiException) {
                                        if (e.status == 401 && hasSession) expireSession()
                                        throw e
                                    }
                                },
                                onDelete = ::deleteProfile,
                                monitoring = store.monitoringEnabled, onMonitoring = ::monitor,
                                serverUrl = store.baseUrl)
                            if (invitation != null) key(invitation.id, hasSession) {
                                IncomingInvitationScreen(invitation, hasSession, busy, invitationError,
                                    load = { loadInvitation(invitation) },
                                    join = { name -> joinInvitation(invitation, name) },
                                    connect = { acceptInvitation(invitation) },
                                    cancel = { cancelInvitation(invitation) })
                            }
                            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    if (hasSession && !busy && pendingInvitation == null) try { refresh() } catch (_: Exception) { /* Explicit refresh reports failures without notification storms. */ }
                    revokePending = store.revokePending
                    delay(15_000)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveInvitation(intent)
    }

    private fun receiveInvitation(incoming: Intent) {
        if (incoming.action != Intent.ACTION_VIEW) return
        val raw = incoming.dataString ?: return
        try {
            saveInvitation(IncomingInvitation.parse(raw))
        } catch (_: Exception) {
            notice("초대 링크 형식이 올바르지 않습니다. 기존 계정과 대기 중인 초대는 유지됩니다. 가족에게 새 링크를 요청해 주세요.")
        } finally {
            // Recreation and history launches must not resurrect an accepted or cancelled link.
            incoming.data = null
        }
    }

    private fun scanInvitation() {
        if (busy || pendingInvitation != null) return
        invitationScanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("어딧 가족 초대 QR을 비춰 주세요")
                .setBeepEnabled(false)
                .setBarcodeImageEnabled(false)
                .setOrientationLocked(false),
        )
    }

    private fun enterScannedInvitation(value: String) {
        try {
            saveInvitation(IncomingInvitation.parse(value))
        } catch (_: Exception) {
            notice("어딧 가족 초대 QR이 아닙니다. 가족이 만든 원래 QR을 다시 비춰 주세요.")
        }
    }

    private fun saveInvitation(invitation: IncomingInvitation) {
        store.saveInvitation(invitation)
        pendingInvitation = invitation
        invitationError = null
    }

    private fun enterInvitation(link: String, server: String, code: String) {
        if (busy || hasSession) return
        try {
            saveInvitation(if (link.isNotBlank()) IncomingInvitation.parse(link) else IncomingInvitation.manual(server, code))
        } catch (e: Exception) { notice(invitationErrorText(e)) }
    }


    private fun cancelInvitation(invitation: IncomingInvitation) {
        if (store.clearInvitation(invitation.id)) {
            pendingInvitation = null
            invitationError = null
        }
    }

    private suspend fun verifyInvitationServer(invitation: IncomingInvitation) {
        if (!hasSession || store.token.isBlank()) throw ApiException(401, "이 기기의 연결 정보가 유효하지 않습니다. 초대를 닫고 현재 프로필의 연결 상태를 확인해 주세요.")
        val currentServer = IncomingInvitation.origin(store.baseUrl)
        if (invitation.server == currentServer) return
        // Only the existing authenticated origin may attest to its public alias.
        // Invitation failure must not discard the existing profile.
        val verified = api.request("GET", "/api/state")
        val alias = verified.optJSONObject("onboarding")?.optString("publicBaseUrl")
        val trustedAlias = alias?.let { runCatching { IncomingInvitation.origin(it) }.getOrNull() }
        if (invitation.server != trustedAlias) throw ApiException(409,
            "현재 연결된 서버와 다른 서버의 초대입니다. 보안을 위해 프로필과 서버를 변경하지 않았습니다. 같은 서버의 초대를 요청해 주세요.")
    }

    private suspend fun loadInvitation(invitation: IncomingInvitation): InvitationPreview {
        if (pendingInvitation?.id == invitation.id) invitationError = null
        if (hasSession) verifyInvitationServer(invitation)
        val result = api.previewInvitation(invitation.server, invitation.code)
        require(IncomingInvitation.origin(result.getString("serverUrl")) == invitation.server) {
            "초대 서버 정보가 링크와 일치하지 않습니다. 가족에게 올바른 링크를 요청해 주세요."
        }
        val expiry = result.getString("expiresAt")
        Instant.parse(expiry)
        val isSetup = result.getBoolean("isSetup")
        val inviterName = if (result.isNull("inviterName")) null else result.getString("inviterName")
        require(isSetup || !inviterName.isNullOrBlank()) { "초대한 가족 정보를 확인하지 못했습니다." }
        if (hasSession && isSetup) throw ApiException(409, "첫 기기용 초대는 이미 연결된 프로필에서 사용할 수 없습니다. 가족 연결용 초대를 요청해 주세요.")
        return InvitationPreview(inviterName, isSetup, expiry)
    }

    private fun acceptInvitation(invitation: IncomingInvitation) {
        if (busy || pendingInvitation?.id != invitation.id) return
        lifecycleScope.launch {
            busy = true
            invitationError = null
            try {
                verifyInvitationServer(invitation)
                if (pendingInvitation?.id != invitation.id) return@launch
                api.request("POST", "/api/invites/accept", json("code" to invitation.code))
                // The POST is authoritative even if the subsequent state refresh fails.
                if (store.clearInvitation(invitation.id)) {
                    pendingInvitation = null
                    invitationError = null
                }
                notice("가족과 연결했습니다. 위치 공유 설정은 변경하지 않았습니다.")
                refresh(expireInvalidSession = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (pendingInvitation?.id == invitation.id) invitationError = invitationErrorText(e)
                else notice(e.message ?: "연결 후 상태를 새로고침하지 못했습니다.")
            } finally {
                busy = false
                revokePending = store.revokePending
            }
        }
    }

    private fun notice(message: String) { lifecycleScope.launch { snackbar.showSnackbar(message) } }
    private fun act(block: suspend () -> Unit) {
        if (busy) return
        lifecycleScope.launch {
            busy = true
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is ApiException && e.status == 401 && hasSession) expireSession()
                notice(e.message ?: "요청을 처리하지 못했습니다. 연결 상태를 확인해 주세요.")
            }
            finally { busy = false; revokePending = store.revokePending }
        }
    }
    private fun expireSession() {
        SafetyService.stop(this)
        store.clear()
        state = null
        hasSession = false
    }
    private suspend fun refresh(expireInvalidSession: Boolean = true) {
        val result = try { api.request("GET", "/api/state") }
        catch (e: ApiException) { if (expireInvalidSession && e.status == 401 && hasSession) expireSession(); throw e }
        state = result
        revokePending = store.revokePending
        val me = result.getJSONObject("me")
        val sharing = me.optBoolean("sharing")
        if (!sharing && store.sharingEnabled) {
            store.sharingEnabled = false
            stopService(Intent(this, SafetyService::class.java))
        } else if (sharing && me.optString("id") == store.userId &&
            store.sharingEnabled && !SafetyService.running && hasLocation() &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            SafetyService.start(this)
        }
    }
    private fun joinInvitation(invitation: IncomingInvitation, name: String) {
        if (busy || hasSession || pendingInvitation?.id != invitation.id) return
        lifecycleScope.launch {
            busy = true
            invitationError = null
            var joined = false
            try {
                require(name.isNotBlank()) { "가족에게 보일 이름을 입력해 주세요." }
                val result = api.joinInvitation(invitation.server, invitation.code, name)
                val user = result.getJSONObject("user")
                // Commit all credentials together before refreshing or clearing the invitation UI.
                store.saveJoinedSession(invitation, result.getString("token"), user.getString("id"))
                joined = true
                state = json("me" to user)
                hasSession = true
                pendingInvitation = store.pendingInvitation()
                invitationError = null
                notice("이 기기를 연결했습니다. 위치 공유는 꺼져 있습니다.")
                refresh(expireInvalidSession = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!joined && pendingInvitation?.id == invitation.id) invitationError = invitationErrorText(e)
                else if (joined) notice("기기 연결은 저장했습니다. 가족 정보를 불러오지 못했습니다. ${e.message ?: "다시 불러오기를 눌러 주세요."}")
                else notice(invitationErrorText(e))
            } finally {
                busy = false
                revokePending = store.revokePending
            }
        }
    }
    private fun hasLocation() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun askNotifications() { if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    private fun changeSharing(enable: Boolean) {
        if (!enable) {
            SafetyService.stop(this)
            revokePending = store.revokePending
            act { api.revokeSharing(); refresh(); notice("위치 공유를 중단하고 서버의 위치 기록을 삭제했습니다.") }
        } else if (!hasLocation()) {
            permissionAction = "share"
            locationPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else enableSharing()
    }
    private fun enableSharing() = act {
        if (store.revokePending) { api.revokeSharing() }
        api.request("PATCH", "/api/me", json("sharing" to true))
        try { store.sharingEnabled = true; SafetyService.start(this) }
        catch (e: Exception) { api.revokeSharing(); throw e }
        refresh()
        askNotifications()
        notice("위치 공유 중입니다. 알림 또는 앱에서 언제든 중단할 수 있습니다.")
    }
    private fun monitor(enable: Boolean) {
        store.monitoringEnabled = enable
        if (enable) { AlertWorker.enqueue(this); askNotifications() } else AlertWorker.cancel(this)
        state = state?.let { JSONObject(it.toString()) }
        notice(if (enable) "백그라운드 보호자 알림을 켰습니다. Android 정책에 따라 15분 이상 지연될 수 있습니다." else "백그라운드 보호자 알림을 껐습니다.")
    }
    private fun deleteProfile(confirmName: String) = act {
        // Keep this session available if server-side deletion fails.
        api.request("DELETE", "/api/me", json("confirmName" to confirmName))
        store.sharingEnabled = false
        store.revokePending = false
        stopService(Intent(this, SafetyService::class.java))
        AlertWorker.cancel(this)
        store.clear()
        withContext(Dispatchers.IO) { File(cacheDir, "evidence").deleteRecursively() }
        state = null; hasSession = false
    }
}

@Composable private fun InvitationLandingScreen(busy: Boolean, initialUrl: String, scan: () -> Unit, submit: (String, String, String) -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    var manual by rememberSaveable { mutableStateOf(false) }
    var base by rememberSaveable { mutableStateOf(initialUrl) }
    var code by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(66.dp).background(Violet, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.VerifiedUser, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
        Text("서로의 일상에,\n조금 더 안심을.", fontSize = 31.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("어딧", color = Violet, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("가족이 보낸 초대로 시작하세요.\n이름만 정하면 이 기기를 연결할 수 있어요.", color = Muted, lineHeight = 23.sp)
        SafetyCard {
            Text("받은 가족 초대 열기", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Button(scan, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Rounded.QrCodeScanner, null)
                Spacer(Modifier.width(10.dp))
                Text("초대 QR 스캔")
            }
            Text("설치 뒤에도 같은 QR을 비추면 코드를 다시 입력하지 않고 초대를 확인할 수 있어요.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
            HorizontalDivider()
            Text("초대 정보를 직접 받은 경우", color = Muted, fontSize = 12.sp)
            if (manual) {
                OutlinedTextField(base, { base = it }, enabled = !busy, label = { Text("초대한 가족의 서버 주소") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(code, { code = it }, enabled = !busy, label = { Text("16자리 초대 코드") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth())
                Text("서버 주소와 코드를 초대한 가족에게 확인해 주세요. 코드는 10분 동안 한 번만 사용할 수 있습니다.", color = Muted, fontSize = 12.sp)
            } else {
                OutlinedTextField(link, { link = it }, enabled = !busy, label = { Text("초대 링크 붙여넣기") }, placeholder = { Text("https://family.example.com/invite/…") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                Text("가족이 보낸 HTTPS 링크 또는 ansimlink:// 초대 링크를 입력해 주세요.", color = Muted, fontSize = 12.sp)
            }
            OutlinedButton({ submit(if (manual) "" else link, base, code) }, enabled = !busy && (if (manual) base.isNotBlank() && IncomingInvitation.validCode(code.trim()) else link.isNotBlank()), modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                Text("초대 확인하기"); Spacer(Modifier.width(10.dp)); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, modifier = Modifier.size(18.dp))
            }
            TextButton({ manual = !manual }, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(if (manual) "초대 링크로 입력하기" else "서버 주소와 코드로 입력하기") }
        }
        InfoStrip("다음 화면에서 초대한 가족과 서버를 확인한 뒤 직접 연결합니다. 위치 공유는 별도로 동의하기 전까지 꺼져 있습니다.", Violet)
        Text("가족의 첫 기기는 서버 운영자에게 첫 기기용 초대를 요청하세요. 앱을 삭제한 뒤 재설치하거나 데이터를 지우면 새 초대가 필요하며, 이전 프로필은 자동 복구되지 않습니다.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
        Text("위급할 때는 112 · 119\n어딧은 긴급 구조기관에 신고하지 않습니다.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
    }
}



@Composable internal fun SafetyScreen(state: JSONObject?, busy: Boolean, pickPlace: (Boolean) -> Unit, mutate: (String, String, JSONObject?) -> Unit) {
    var deletingZone by remember { mutableStateOf<JSONObject?>(null) }
    SafetyCard {
        Icon(Icons.Rounded.Radar, null, tint = Violet)
        Text("나의 안심존", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text("내 기기가 지정한 구역을 드나들면 가족에게 알립니다. 위치 공유가 켜져 있어야 동작합니다.", color = Muted, fontSize = 13.sp)
        state?.array("zones")?.forEach { zone -> Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(zone.optString("name"), fontWeight = FontWeight.SemiBold); Text("반경 ${zone.optInt("radius")}m", color = Muted, fontSize = 11.sp) }
            IconButton({ deletingZone = zone }, enabled = !busy) { Icon(Icons.Rounded.DeleteOutline, "안심존 삭제", tint = Muted) }
        } }
        if (state?.array("zones").orEmpty().isEmpty()) Text("집, 학교 등 자주 가는 곳을 등록하세요.", color = Muted, fontSize = 13.sp)
        OutlinedButton({ pickPlace(false) }, enabled = !busy && state != null) { Icon(Icons.Rounded.AddLocationAlt, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("지도에서 안심존 등록") }
    }
    SafetyCard {
        Icon(Icons.Rounded.NearMe, null, tint = Mint)
        Text("안심귀가", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text("목적지와 도착 예정 시간을 가족에게 공유합니다. 도착하거나 예정 시간을 넘기면 알려드립니다.", color = Muted, fontSize = 13.sp)
        state?.array("journeys")?.forEach { journey ->
            val status = journey.optString("status")
            val label = when (status) { "active" -> "이동 중"; "overdue" -> "예정 시간 초과"; "arrived" -> "도착 완료"; "cancelled" -> "취소됨"; else -> status }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("${journey.optString("userName")} → ${journey.optString("destination")}", fontWeight = FontWeight.SemiBold); Text("$label · ${whenText(journey.optString("deadline"))}", color = if (status == "overdue") Danger else Muted, fontSize = 12.sp) }
                if (journey.optString("userId") == state.optJSONObject("me")?.optString("id") && status in listOf("active", "overdue")) TextButton({ mutate("POST", "/api/journeys/${journey.getString("id")}/cancel", json()) }, enabled = !busy) { Text("종료") }
            }
        }
        Button({ pickPlace(true) }, enabled = !busy && state?.optJSONObject("me")?.optBoolean("sharing") == true) { Text("지도에서 안심귀가 시작") }
        if (state?.optJSONObject("me")?.optBoolean("sharing") != true) Text("홈에서 내 위치 공유를 먼저 켜 주세요.", color = Muted, fontSize = 12.sp)
    }
    deletingZone?.let { zone ->
        AlertDialog(onDismissRequest = { deletingZone = null }, title = { Text("안심존을 삭제할까요?") },
            text = { Text("${zone.optString("name")}의 진입·이탈 알림이 중단됩니다.") },
            confirmButton = { TextButton({ mutate("DELETE", "/api/zones/${zone.getString("id")}", null); deletingZone = null }, enabled = !busy) { Text("삭제", color = Danger) } },
            dismissButton = { TextButton({ deletingZone = null }) { Text("취소") } })
    }
}





@Composable
internal fun PlacePickerScreen(
    journey: Boolean,
    current: JSONObject?,
    busy: Boolean,
    onBack: () -> Unit,
    onSubmit: (JSONObject) -> Unit,
) {
    BackHandler(onBack = onBack)
    val initial = remember { current?.let { JSONObject(it.toString()) } }
    val mapState = rememberFamilyMapState(initial, 16.0)
    var center by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var name by rememberSaveable(journey) { mutableStateOf("") }
    var radius by rememberSaveable(journey) { mutableFloatStateOf(150f) }
    var minutes by rememberSaveable(journey) { mutableStateOf("30") }
    val radiusValue = radius.roundToInt()
    val duration = minutes.toLongOrNull()
    val valid = center != null && name.isNotBlank() && radiusValue in 50..5000 &&
        (!journey || duration != null && duration in 1..1440)
    val radiusLabel = if (radiusValue >= 1000) String.format(Locale.US, "%.1f km", radiusValue / 1000.0) else "${radiusValue} m"

    Column(Modifier.fillMaxSize().background(Canvas).imePadding()) {
        ScreenHeader(if (journey) "안심귀가 목적지 선택" else "안심존 위치 선택", onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            FamilyMap(
                state = null,
                modifier = Modifier.fillMaxSize(),
                mapState = mapState,
                pickerMode = true,
                pickerRadius = radiusValue.toDouble(),
                onPickerCenter = { latitude, longitude ->
                    val next = latitude to longitude
                    if (center != next) center = next
                },
            )
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                color = Color.White.copy(alpha = 0.96f),
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 3.dp,
            ) {
                Text("지도를 움직여 가운데 표시에 위치를 맞추세요", Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = Ink, fontSize = 12.sp)
            }
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 6.dp,
            ) {
                Icon(Icons.Rounded.Place, "선택할 위치", Modifier.padding(9.dp).size(28.dp), tint = Violet)
            }
        }
        Surface(color = Color.White, shadowElevation = 8.dp) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text(if (journey) "목적지 이름" else "장소 이름") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("반경", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(radiusLabel, color = Violet, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = radius,
                    onValueChange = { radius = ((it / 50f).roundToInt() * 50f).coerceIn(50f, 5000f) },
                    valueRange = 50f..5000f,
                    steps = 98,
                )
                if (journey) OutlinedTextField(
                    minutes,
                    { minutes = it },
                    label = { Text("몇 분 후 도착 예정인가요?") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val selected = center ?: return@Button
                        onSubmit(json(
                            (if (journey) "destination" else "name") to name.trim(),
                            "latitude" to selected.first,
                            "longitude" to selected.second,
                            "radius" to radiusValue,
                        ).apply {
                            if (journey) put("deadline", Instant.now().plusSeconds(duration!! * 60).toString())
                        })
                    },
                    enabled = valid && !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(if (journey) "이 위치로 안심귀가 시작" else "이 위치로 안심존 등록") }
            }
        }
    }
}

@Composable internal fun MemberRow(member: JSONObject) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(Color(0xFFEEEDFF), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) { Text(member.optString("name").take(1), color = Violet, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(member.optString("name"), fontWeight = FontWeight.Bold)
            val location = member.optJSONObject("location")
            Text(if (!member.optBoolean("sharing")) "위치 공유 꺼짐" else if (location == null) "첫 위치 수신 대기" else "마지막 수신 ${whenText(location.optString("recordedAt"))}", color = Muted, fontSize = 12.sp)
        }
        Icon(if (member.optJSONObject("location") != null) Icons.Rounded.LocationOn else Icons.Rounded.LocationOff, null, tint = if (member.optJSONObject("location") != null) Mint else Muted, modifier = Modifier.size(20.dp))
    }
}
@Composable internal fun SafetyCard(content: @Composable ColumnScope.() -> Unit) { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) } }
@Composable internal fun SectionTitle(title: String, aside: String) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp); Spacer(Modifier.weight(1f)); Text(aside, color = Muted, fontSize = 12.sp) } }
@Composable internal fun InfoStrip(text: String, tint: Color) { Surface(color = tint.copy(alpha = 0.08f), shape = RoundedCornerShape(12.dp)) { Text(text, Modifier.padding(12.dp), color = tint, fontSize = 12.sp, lineHeight = 19.sp) } }
@Composable internal fun EmptyCard(icon: ImageVector, title: String, subtitle: String) { SafetyCard { Icon(icon, null, tint = Violet, modifier = Modifier.size(30.dp)); Text(title, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 21.sp) } }
