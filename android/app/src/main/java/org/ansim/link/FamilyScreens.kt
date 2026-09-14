package org.ansim.link

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

private enum class FamilyPage { Landing, Invite, Join }

@Composable
internal fun FamilyScreen(
    state: JSONObject?,
    busy: Boolean,
    onInvite: ((JSONObject) -> Unit) -> Unit,
    acceptInvite: (String, () -> Unit) -> Unit,
    scanInvitation: () -> Unit,
    mutate: (String, String, JSONObject?) -> Unit,
    onMember: (String) -> Unit,
    onBack: () -> Unit,
) {
    val currentOwnerId = state?.optJSONObject("me")?.optString("id")
    // Account state reloads briefly after rotation; do not discard an unexpired QR.
    var retainedOwnerId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(currentOwnerId) { if (currentOwnerId != null) retainedOwnerId = currentOwnerId }
    val ownerId = currentOwnerId ?: retainedOwnerId
    var page by rememberSaveable(ownerId) { mutableStateOf(FamilyPage.Landing) }
    var code by rememberSaveable(ownerId) { mutableStateOf("") }
    var invitationCode by rememberSaveable(ownerId) { mutableStateOf("") }
    var invitationExpiry by rememberSaveable(ownerId) { mutableStateOf("") }
    var invitationUrl by rememberSaveable(ownerId) { mutableStateOf("") }
    var invitationApkAvailable by rememberSaveable(ownerId) { mutableStateOf(false) }
    var disconnectId by rememberSaveable(ownerId) { mutableStateOf<String?>(null) }
    val members = state?.array("members").orEmpty()
    val disconnectMember = members.firstOrNull { it.optString("id") == disconnectId }
    val focusManager = LocalFocusManager.current
    val back: () -> Unit = {
        focusManager.clearFocus()
        if (page == FamilyPage.Landing) onBack() else page = FamilyPage.Landing
    }
    BackHandler(enabled = disconnectMember == null, onBack = back)

    Column(Modifier.fillMaxSize().background(Canvas).imePadding()) {
        ScreenHeader(
            title = when (page) {
                FamilyPage.Landing -> "우리 가족"
                FamilyPage.Invite -> "가족 초대하기"
                FamilyPage.Join -> "받은 초대로 연결하기"
            },
            onBack = back,
        )
        when (page) {
            FamilyPage.Landing -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Text("함께할 가족을 연결해요", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Spacer(Modifier.height(8.dp))
                    Text("가족 연결과 위치 공유는 별개예요. 서로 연결한 뒤에도 위치 공유는 각자 동의하고 켜야 합니다.", color = Muted, lineHeight = 22.sp)
                }
                item {
                    FamilyEntry(
                        "가족 초대하기", "QR로 설치부터 가족 연결까지 이어져요", Icons.Rounded.QrCode2,
                        enabled = state != null,
                    ) { page = FamilyPage.Invite }
                }
                item {
                    FamilyEntry(
                        "받은 초대로 연결하기", "QR을 스캔하거나 코드를 입력해요", Icons.Rounded.QrCodeScanner,
                        enabled = state != null,
                    ) { page = FamilyPage.Join }
                }
                item { SectionTitle("연결된 가족", if (state == null) "불러오는 중" else "${members.size}명") }
                if (state == null) {
                    item { InfoStrip("가족 정보를 불러오는 중입니다. 아직 연결 상태를 확인할 수 없어요.", Muted) }
                } else if (members.isEmpty()) {
                    item { EmptyCard(Icons.Rounded.PeopleOutline, "아직 연결된 가족이 없어요", "초대 QR을 스캔하거나 코드를 주고받아 연결해 보세요.") }
                }
                items(members, key = { it.optString("id") }) { member ->
                    val memberId = member.optString("id")
                    val name = member.optString("name").ifBlank { "가족" }
                    SafetyCard {
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(
                                role = Role.Button,
                                onClickLabel = "${name}님의 지도 보기",
                                onClick = { onMember(memberId) },
                            ),
                            contentAlignment = Alignment.CenterStart,
                        ) { MemberRow(member) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton({ onMember(memberId) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("지도에서 보기") }
                            TextButton(
                                { disconnectId = memberId }, enabled = !busy,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            ) { Text("연결 해제", color = Danger) }
                        }
                    }
                }
            }
            FamilyPage.Invite -> InviteFlow(
                code = invitationCode,
                expiresAt = invitationExpiry,
                inviteUrl = invitationUrl,
                apkAvailable = invitationApkAvailable,
                busy = busy,
                enabled = state != null,
                create = {
                    onInvite { invitation ->
                        invitationCode = invitation.optString("code")
                        invitationExpiry = invitation.optString("expiresAt")
                        invitationUrl = if (invitation.isNull("inviteUrl")) "" else invitation.getString("inviteUrl")
                        invitationApkAvailable = invitation.optBoolean("apkAvailable")
                    }
                },
                modifier = Modifier.weight(1f),
            )
            FamilyPage.Join -> Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text("받은 초대로 가족과 연결해요", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("초대한 가족과 같은 서버에 연결되어 있는지 확인해 주세요. 초대는 만든 뒤 10분 동안 한 번만 사용할 수 있어요.", color = Muted, lineHeight = 23.sp)
                Button(
                    onClick = scanInvitation,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Rounded.QrCodeScanner, null)
                    Spacer(Modifier.width(8.dp))
                    Text("초대 QR 스캔")
                }
                Text("QR을 비추면 코드 입력 없이 초대한 가족과 서버를 확인하는 화면으로 이동합니다.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                HorizontalDivider()
                Text("코드를 직접 받은 경우", color = Muted, fontSize = 12.sp)
                SafetyCard {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("받은 초대 코드") },
                        supportingText = { Text("대소문자와 기호를 포함해 받은 그대로 입력하세요.") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("연결하면 서로 가족 목록에 표시됩니다. 위치와 이동 기록은 각자 위치 공유에 동의한 경우에만 볼 수 있어요.", color = Muted, lineHeight = 22.sp)
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            acceptInvite(code.trim()) { code = ""; page = FamilyPage.Landing }
                        },
                        enabled = !busy && state != null && IncomingInvitation.validCode(code.trim()),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text(if (busy) "처리 중…" else "동의하고 가족 연결") }
                }
                TextButton(back, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("가족 목록으로 돌아가기") }
            }
        }
    }

    disconnectMember?.let { member ->
        val name = member.optString("name").ifBlank { "가족" }
        AlertDialog(
            onDismissRequest = { disconnectId = null },
            title = { Text("가족 연결을 해제할까요?") },
            text = {
                Text(
                    "${name}님과 서로의 위치와 이동 기록을 더 이상 볼 수 없습니다. 다시 연결하려면 새 초대 코드가 필요해요.",
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        mutate("DELETE", "/api/members/${member.optString("id")}", null)
                        disconnectId = null
                    },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("연결 해제", color = Danger) }
            },
            dismissButton = {
                TextButton({ disconnectId = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") }
            },
        )
    }
}

@Composable
private fun FamilyEntry(title: String, description: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
        shape = RoundedCornerShape(20.dp), color = Color.White,
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = Violet, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(description, color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Muted)
        }
    }
}

@Composable
private fun InvitationQr(url: String) {
    val image by produceState<Result<ImageBitmap>?>(initialValue = null, url) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val size = 512
                val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 4))
                val pixels = IntArray(size * size) { index -> if (matrix[index % size, index / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
            }
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val bitmap = image?.getOrNull()
        when {
            bitmap != null -> Image(bitmap, "가족 폰 카메라로 스캔할 초대 QR 코드", Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(1f), filterQuality = FilterQuality.None)
            image == null -> Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> InfoStrip("QR을 표시하지 못했어요. 초대 링크를 복사해서 전달해 주세요.", Danger)
        }
    }
}

@Composable
private fun InviteFlow(code: String, expiresAt: String, inviteUrl: String, apkAvailable: Boolean, busy: Boolean, enabled: Boolean, create: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val expiry = remember(expiresAt) { parseTimestamp(expiresAt) }
    var expired by remember(expiresAt) { mutableStateOf(expiry?.isAfter(Instant.now()) != true) }
    var feedback by remember(code) { mutableStateOf<String?>(null) }
    val hasLink = inviteUrl.isNotBlank()
    LaunchedEffect(expiry) {
        while (expiry != null && expiry.isAfter(Instant.now())) {
            delay(Duration.between(Instant.now(), expiry).toMillis().coerceIn(1L, 1_000L))
        }
        expired = true
    }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("QR로 가족을 초대해요", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("가족 폰에 앱이 있으면 어딧의 ‘초대 QR 스캔’으로 바로 초대를 확인할 수 있어요. 앱이 없으면 기본 카메라로 설치 안내를 여세요.", color = Muted, lineHeight = 23.sp)
        SafetyCard {
            if (code.isBlank()) {
                Icon(Icons.Rounded.QrCode2, null, tint = Violet, modifier = Modifier.size(36.dp))
                Text("가족이 준비되면 QR을 만들어 주세요", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("초대는 10분 동안 한 사람이 사용할 수 있어요. 가족 폰에도 이 서버에 접속할 수 있는 네트워크 연결이 필요합니다.", color = Muted, lineHeight = 22.sp)
            } else {
                if (expired) {
                    InfoStrip("초대가 만료되었어요. 아래에서 새 QR을 만들어 주세요.", Danger)
                } else {
                    if (hasLink) {
                        Text("이 QR을 가족 폰으로 비춰 주세요", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        InvitationQr(inviteUrl)
                        Text(if (apkAvailable) "앱 있음: 어딧에서 QR 스캔 · 앱 없음: 기본 카메라로 설치 후 같은 QR 다시 스캔" else "설치된 어딧 앱의 ‘초대 QR 스캔’으로 비춰 주세요.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                        if (!apkAvailable) InfoStrip("이 서버는 Android 설치 파일을 제공하지 않습니다. 앱이 없는 가족에게는 신뢰할 수 있는 별도 설치 경로를 안내해 주세요.", Muted)
                    } else {
                        InfoStrip("QR 초대를 제공하려면 서버에 공개 접속 주소를 설정해야 합니다. 같은 서버에 연결된 가족은 아래 코드를 직접 입력할 수 있어요.", Muted)
                    }
                    Text("만료 ${whenText(expiresAt)} · 한 사람만 사용 가능", color = Muted, fontSize = 13.sp)
                    if (hasLink) {
                        SelectionContainer { Text(inviteUrl, color = Muted, fontSize = 12.sp, lineHeight = 18.sp) }
                        Text("Tailscale 주소를 사용한다면 가족 폰도 Tailscale에 연결되어 있어야 해요.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            if (expiry?.isAfter(Instant.now()) == true) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText(if (hasLink) "가족 초대 링크" else "가족 초대 코드", if (hasLink) inviteUrl else code))
                                feedback = if (hasLink) "초대 링크를 복사했어요." else "초대 코드를 복사했어요."
                            } else expired = true
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (hasLink) "초대 링크 복사" else "코드 복사")
                    }
                    OutlinedButton(
                        onClick = {
                            if (expiry?.isAfter(Instant.now()) == true) {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, if (hasLink) "어딧 가족 초대입니다.\n$inviteUrl\n만료: ${whenText(expiresAt)} (한 사람만 사용 가능)\n앱이 있으면 어딧의 ‘초대 QR 스캔’으로 링크의 QR을 비추거나 이 링크를 여세요. 앱이 없으면 설치 후 같은 QR을 다시 스캔할 수 있습니다. Tailscale 주소라면 가족 폰도 Tailscale 연결이 필요합니다. 위치 공유는 자동으로 켜지지 않습니다."
                                        else "어딧 초대 코드: $code\n만료: ${whenText(expiresAt)} (한 사람만 사용 가능)\n초대한 가족의 서버 주소와 함께 입력해 주세요. 이미 같은 서버에 연결된 기기는 ‘받은 초대로 연결하기’를 이용해 주세요.")
                                }
                                try { context.startActivity(Intent.createChooser(share, "가족 초대 전달")) }
                                catch (_: ActivityNotFoundException) { feedback = "공유할 앱이 없어요. 초대 링크나 코드를 복사해 주세요." }
                                catch (_: SecurityException) { feedback = "공유 화면을 열 수 없어요. 초대 링크나 코드를 복사해 주세요." }
                            } else expired = true
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Rounded.Share, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("전달할 앱 선택")
                    }
                    Text("앱에서 직접 입력할 코드", color = Muted, fontSize = 12.sp)
                    SelectionContainer { Text(code, color = Violet, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                }
            }
            feedback?.let { Text(it, color = Muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            Button(
                onClick = { feedback = null; create() },
                enabled = enabled && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text(if (busy) "처리 중…" else if (code.isBlank()) "초대 QR 만들기" else "새 초대 QR 만들기") }
            if (code.isNotBlank()) Text("새 QR을 만들면 이전 초대는 더 이상 사용할 수 없어요.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
        }
        InfoStrip("가족 연결만으로 위치 공유가 켜지지는 않습니다. 이미 공유 중인 계정의 위치와 기록은 새 가족에게도 보일 수 있어요. 메시지는 직접 전달해야 합니다.", Violet)
    }
}

private data class RecordedFix(val point: JSONObject, val time: Instant)
private data class DailyHistory(val points: List<JSONObject>, val first: Instant?, val last: Instant?)
private data class HistoryCalendar(val zone: ZoneId, val today: LocalDate)
private sealed interface HistoryResult {
    data object Loading : HistoryResult
    data object Cancelled : HistoryResult
    data class Ready(val fixes: List<RecordedFix>) : HistoryResult
    data class Failed(val message: String) : HistoryResult
}

@Composable
internal fun HistoryScreen(
    personId: String,
    personName: String,
    available: Boolean,
    load: suspend (String) -> List<JSONObject>,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    // A permission/identity change replaces all state and the native map in the same composition.
    key(personId, available) {
        PersonHistory(personId, personName, available, load, onBack)
    }
}

@Composable
private fun PersonHistory(personId: String, personName: String, available: Boolean, load: suspend (String) -> List<JSONObject>, onBack: () -> Unit) {
    var result by remember { mutableStateOf<HistoryResult>(HistoryResult.Loading) }
    var request by remember { mutableIntStateOf(0) }
    var loadingEnabled by remember { mutableStateOf(true) }
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    val latestLoad by rememberUpdatedState(load)
    val calendar by produceState(HistoryCalendar(ZoneId.systemDefault(), LocalDate.now())) {
        while (true) {
            val zone = ZoneId.systemDefault()
            value = HistoryCalendar(zone, LocalDate.now(zone))
            delay(30_000L)
        }
    }
    val days = remember(calendar.today) { (0L..6L).map { calendar.today.minusDays(it) } }
    val day = selectedDate?.let { saved -> days.firstOrNull { it.toString() == saved } } ?: calendar.today
    val dateFormat = remember { DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", Locale.KOREAN) }
    val chipDateFormat = remember { DateTimeFormatter.ofPattern("M/d", Locale.KOREAN) }
    val weekdayFormat = remember { DateTimeFormatter.ofPattern("E", Locale.KOREAN) }
    val timeFormat = remember(calendar.zone) { DateTimeFormatter.ofPattern("HH:mm", Locale.KOREAN).withZone(calendar.zone) }
    val retry: () -> Unit = {
        result = HistoryResult.Loading
        loadingEnabled = true
        request++
    }

    LaunchedEffect(personId, available, request, loadingEnabled) {
        if (!available || !loadingEnabled) return@LaunchedEffect
        result = HistoryResult.Loading
        try {
            val points = latestLoad(personId)
            currentCoroutineContext().ensureActive()
            val fixes = withContext(Dispatchers.Default) {
                points.mapNotNull { point ->
                    currentCoroutineContext().ensureActive()
                    val time = parseTimestamp(point.optString("recordedAt"))
                    val latitude = point.optDouble("latitude")
                    val longitude = point.optDouble("longitude")
                    if (time != null && latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0) {
                        RecordedFix(point, time)
                    } else null
                }
            }
            currentCoroutineContext().ensureActive()
            result = HistoryResult.Ready(fixes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            result = HistoryResult.Failed(error.message?.takeIf { it.isNotBlank() } ?: "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.")
        }
    }
    val daily = remember(result, day, calendar.zone) {
        (result as? HistoryResult.Ready)?.let { historyForDay(it.fixes, day, calendar.zone) }
    }

    Column(Modifier.fillMaxSize().background(Canvas)) {
        ScreenHeader("이동 기록", onBack) {
            IconButton(onClick = retry, enabled = available && result !is HistoryResult.Loading, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.Refresh, "이동 기록 새로고침", tint = Violet)
            }
        }
        if (!available) {
            HistoryMessage {
                EmptyCard(Icons.Rounded.LocationOff, "현재 기록을 볼 수 없어요", "가족 연결이 해제되었거나 위치 공유가 꺼졌습니다. 이전 기록도 더 이상 표시하지 않아요.")
            }
        } else {
            BoxWithConstraints(Modifier.weight(1f)) {
                val headerLimit = maxHeight * 0.48f
                Column(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = headerLimit).verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("${personName.ifBlank { "가족" }}님의 이동 기록", Modifier.padding(horizontal = 20.dp), fontWeight = FontWeight.Bold, fontSize = 19.sp, color = Ink)
                        Text(
                            "${dateFormat.format(day)}${if (day == calendar.today) " · 오늘" else ""}",
                            Modifier.padding(horizontal = 20.dp), color = Muted, fontSize = 13.sp,
                        )
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(days, key = { it.toEpochDay() }) { date ->
                                FilterChip(
                                    selected = date == day,
                                    onClick = { selectedDate = if (date == calendar.today) null else date.toString() },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    label = {
                                        Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(if (date == calendar.today) "오늘" else weekdayFormat.format(date))
                                            Text(chipDateFormat.format(date), fontSize = 12.sp)
                                        }
                                    },
                                )
                            }
                        }
                        Text("기기 시간대 기준 · 최근 7일, 최대 2,000개", Modifier.padding(horizontal = 20.dp), color = Muted, fontSize = 12.sp)
                        if (daily != null && daily.points.isNotEmpty()) {
                            val range = if (daily.points.size == 1) timeFormat.format(daily.first) else "${timeFormat.format(daily.first)} – ${timeFormat.format(daily.last)}"
                            Text("위치 ${daily.points.size}개 · $range", Modifier.padding(horizontal = 20.dp), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (val current = result) {
                            HistoryResult.Loading -> HistoryMessage {
                                CircularProgressIndicator(color = Violet, modifier = Modifier.size(32.dp))
                                Text("이동 기록을 불러오는 중이에요", color = Muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                TextButton(
                                    onClick = { loadingEnabled = false; result = HistoryResult.Cancelled },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text("불러오기 취소") }
                            }
                            HistoryResult.Cancelled -> HistoryMessage {
                                Text("불러오기를 취소했어요", fontWeight = FontWeight.SemiBold, color = Ink)
                                OutlinedButton(retry, modifier = Modifier.heightIn(min = 48.dp)) { Text("다시 불러오기") }
                            }
                            is HistoryResult.Failed -> HistoryMessage {
                                EmptyCard(Icons.Rounded.CloudOff, "이동 기록을 불러오지 못했어요", current.message)
                                Button(retry, modifier = Modifier.heightIn(min = 48.dp)) { Text("다시 시도") }
                            }
                            is HistoryResult.Ready -> {
                                if (daily == null || daily.points.isEmpty()) {
                                    HistoryMessage {
                                        EmptyCard(Icons.Rounded.Route, "이 날짜에 표시할 기록이 없어요", "다른 날짜를 선택해 보세요. 위치 공유 중 수집된 최근 기록만 표시됩니다.")
                                    }
                                } else {
                                    key(day) {
                                        FamilyMap(
                                            state = null,
                                            history = daily.points,
                                            modifier = Modifier.fillMaxSize(),
                                            mapState = rememberFamilyMapState(),
                                            historyMode = true,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryMessage(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

private fun historyForDay(fixes: List<RecordedFix>, day: LocalDate, zone: ZoneId): DailyHistory {
    val start = day.atStartOfDay(zone).toInstant()
    val end = day.plusDays(1).atStartOfDay(zone).toInstant()
    var first: Instant? = null
    var last: Instant? = null
    val points = buildList {
        for (fix in fixes) {
            if (!fix.time.isBefore(start) && fix.time.isBefore(end)) {
                add(fix.point)
                if (first == null) first = fix.time
                last = fix.time
            }
        }
    }
    return DailyHistory(points, first, last)
}

private fun parseTimestamp(value: String): Instant? = try {
    Instant.parse(value)
} catch (_: DateTimeParseException) {
    try {
        OffsetDateTime.parse(value).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }
}
