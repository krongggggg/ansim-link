package org.ansim.link

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

private enum class HomePage { Map, Family, History, Safety, PlacePicker, Alerts, Settings }

@Composable internal fun ScreenHeader(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로가기") }
        Text(title, Modifier.weight(1f).padding(horizontal = 8.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

@Composable internal fun HomeScreen(
    state: JSONObject?, busy: Boolean, revokePending: Boolean, collecting: Boolean,
    onRefresh: () -> Unit, onShare: (Boolean) -> Unit, onMutation: (String, String, JSONObject?) -> Unit,
    onInvite: (String, (JSONObject) -> Unit) -> Unit, onHistory: suspend (String) -> List<JSONObject>,
    onAcceptInvite: (String, () -> Unit) -> Unit, onScanInvitation: () -> Unit,
    onDelete: (String) -> Unit, monitoring: Boolean, onMonitoring: (Boolean) -> Unit, serverUrl: String
) {
    var page by rememberSaveable { mutableStateOf(HomePage.Map) }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var recenterRequest by rememberSaveable { mutableIntStateOf(0) }
    var historyId by rememberSaveable { mutableStateOf("") }
    var shareDetails by rememberSaveable { mutableStateOf(false) }
    var shareConfirm by rememberSaveable { mutableStateOf(false) }
    var placeJourney by rememberSaveable { mutableStateOf(false) }
    val mapState = rememberFamilyMapState()
    val visibleState = remember(state, revokePending) {
        if (!revokePending || state == null) state else JSONObject().apply {
            state.keys().forEach { field -> put(field, state.get(field)) }
            state.optJSONObject("me")?.let { put("me", JSONObject(it.toString()).put("sharing", false)) }
            remove("location")
        }
    }
    val me = visibleState?.optJSONObject("me")
    val meId = me?.optString("id").orEmpty()
    val role = me?.optString("role").orEmpty()
    val sharing = role.isNotBlank() && me?.optBoolean("sharing") == true && !revokePending
    val members = remember(state) { state?.array("members").orEmpty() }
    val selected = if (selectedId == meId) me else members.find { it.optString("id") == selectedId }
    val own = selectedId == meId
    val selectedCanView = own || selected?.optBoolean("canViewLocation") == true
    val selectedSharing = selectedCanView && if (own) sharing else selected?.optBoolean("sharing") == true
    val point = if (!selectedSharing) null else if (own) state?.optJSONObject("location") else selected?.optJSONObject("location")
    val back: () -> Unit = { page = if (page == HomePage.PlacePicker) HomePage.Safety else HomePage.Map }
    val choose: (String) -> Unit = { id -> selectedId = id; recenterRequest++; page = HomePage.Map }
    val shareLabel = when {
        revokePending -> "공유 해제 확인 중"
        sharing && !collecting -> "위치 수집 중단"
        sharing -> "내 위치 공유 중"
        else -> "내 위치 공유 꺼짐"
    }
    LaunchedEffect(meId, members) {
        if (meId.isNotBlank() && selectedId != meId && members.none { it.optString("id") == selectedId }) {
            selectedId = meId
            recenterRequest++
        }
    }
    BackHandler(page != HomePage.Map) { back() }
    when (page) {
        HomePage.Map -> MapDashboard(
            visibleState, me, members, selected, point, own, selectedCanView, selectedSharing, sharing, shareLabel, busy,
            mapState, selectedId, recenterRequest, choose,
            onRefresh = onRefresh,
            onShareDetails = { shareDetails = true },
            onConnect = { page = HomePage.Family },
            onAlerts = { page = HomePage.Alerts },
            onSettings = { page = HomePage.Settings },
            onHistory = { historyId = selectedId; page = HomePage.History },
            onSafety = { page = HomePage.Safety },
            onEnableSharing = { shareConfirm = true },
            onRecenter = { recenterRequest++ }
        )
        HomePage.Family -> FamilyScreen(state, busy, onInvite, onAcceptInvite, onScanInvitation, onMutation, choose, back)
        HomePage.History -> {
            val person = if (historyId == meId) me else members.find { it.optString("id") == historyId }
            HistoryScreen(historyId, person?.optString("name") ?: "연결 해제된 가족", person != null && (if (historyId == meId) sharing else person.optBoolean("canViewLocation") && person.optBoolean("sharing")), onHistory, back)
        }
        HomePage.PlacePicker -> PlacePickerScreen(
            journey = placeJourney,
            current = state?.optJSONObject("location"),
            busy = busy,
            onBack = { page = HomePage.Safety },
            onSubmit = { body ->
                onMutation("POST", if (placeJourney) "/api/journeys" else "/api/zones", body)
                page = HomePage.Safety
            },
        )
        HomePage.Safety, HomePage.Alerts, HomePage.Settings -> Column(Modifier.fillMaxSize()) {
            ScreenHeader(when (page) { HomePage.Safety -> "내 안심존 · 안심귀가"; HomePage.Alerts -> "가족 소식"; else -> "설정" }, back) {
                IconButton(onRefresh, enabled = !busy) { Icon(Icons.Rounded.Refresh, "새로고침") }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (page) {
                    HomePage.Safety -> SafetyScreen(state, busy, { journey -> placeJourney = journey; page = HomePage.PlacePicker }, onMutation)
                    HomePage.Settings -> {
                        OutlinedButton({ shareDetails = true }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.LocationOn, null); Spacer(Modifier.width(8.dp)); Text(shareLabel) }
                        SettingsScreen(me, busy, monitoring, onMonitoring, onMutation, onDelete, serverUrl)
                    }
                    else -> {
                        Text("가족 연결과 안심존, 안심귀가 소식을 확인하세요. 앱이 열려 있는 동안 약 15초마다 갱신됩니다.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                        val events = state?.array("events").orEmpty()
                        if (events.isEmpty()) EmptyCard(Icons.Rounded.NotificationsNone, "아직 도착한 소식이 없어요", "새 소식이 도착하면 이곳에 시간순으로 표시됩니다.")
                        events.forEach { event -> SafetyCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.NotificationsNone, null, tint = Violet)
                                Spacer(Modifier.width(10.dp))
                                Text(event.optString("title"), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            }
                            Text(event.optString("body"), color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
                            Text(whenText(event.optString("createdAt")), color = Muted, fontSize = 12.sp)
                        } }
                    }
                }
            }
        }
    }
    if (shareDetails) AlertDialog(
        onDismissRequest = { shareDetails = false },
        icon = { Icon(if (sharing) Icons.Rounded.LocationOn else Icons.Rounded.LocationOff, null, tint = if (sharing) Mint else Muted) },
        title = { Text("내 위치 공유") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(shareLabel, fontWeight = FontWeight.Bold)
                Text(if (role == "guardian") "내 위치는 연결된 보호자에게만 공유합니다. 피보호자에게는 현재 위치와 이동 기록을 제공하지 않습니다." else "내 위치는 연결된 보호자와 피보호자에게 공유합니다. 각 사용자가 직접 공유를 켜야 합니다.")
                if (revokePending) InfoStrip("이 기기의 수집은 중단했습니다. 서버의 공유 해제는 연결 후 완료됩니다.", Danger)
                else if (sharing && !collecting) InfoStrip("서버 공유는 켜져 있지만 이 기기는 수집 중이 아닙니다. 직접 다시 시작할 수 있습니다.", Danger)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("위치 공유", Modifier.weight(1f))
                    Switch(sharing, { enable -> shareDetails = false; if (enable) shareConfirm = true else onShare(false) }, enabled = !busy && me != null, modifier = Modifier.semantics { contentDescription = "내 위치 공유 설정" })
                }
                if (sharing && !collecting && !revokePending) OutlinedButton({ shareDetails = false; shareConfirm = true }, enabled = !busy) { Text("이 기기에서 수집 다시 시작") }
                TextButton({ shareDetails = false; page = HomePage.Safety }) { Text("내 안심존 · 안심귀가 관리") }
            }
        },
        confirmButton = { TextButton({ shareDetails = false }) { Text("닫기") } }
    )
    if (shareConfirm) AlertDialog(
        onDismissRequest = { shareConfirm = false }, icon = { Icon(Icons.Rounded.LocationOn, null) }, title = { Text("내 위치를 공유할까요?") },
        text = { Text(if (role == "guardian") "연결된 보호자만 현재 위치와 최근 7일 이동 기록을 볼 수 있습니다. 피보호자에게는 제공되지 않습니다. 앱을 벗어나도 알림을 표시하며 위치를 수집합니다. 공유를 끄면 서버의 위치 기록이 삭제됩니다.\n\n기기 설정에 따라 위치 수집이 중단될 수 있습니다." else "연결된 보호자와 피보호자가 현재 위치와 최근 7일 이동 기록을 볼 수 있습니다. 앱을 벗어나도 알림을 표시하며 위치를 수집합니다. 공유를 끄면 서버의 위치 기록이 삭제됩니다.\n\n기기 설정에 따라 위치 수집이 중단될 수 있습니다.") },
        confirmButton = { TextButton({ shareConfirm = false; onShare(true) }, enabled = !busy) { Text("동의하고 공유") } },
        dismissButton = { TextButton({ shareConfirm = false }) { Text("취소") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MapDashboard(
    state: JSONObject?, me: JSONObject?, members: List<JSONObject>, person: JSONObject?, point: JSONObject?, own: Boolean,
    canViewLocation: Boolean, personSharing: Boolean, sharing: Boolean, shareLabel: String, busy: Boolean,
    mapState: FamilyMapState, selectedId: String, recenterRequest: Int, onSelect: (String) -> Unit,
    onRefresh: () -> Unit, onShareDetails: () -> Unit, onConnect: () -> Unit, onAlerts: () -> Unit, onSettings: () -> Unit,
    onHistory: () -> Unit, onSafety: () -> Unit, onEnableSharing: () -> Unit, onRecenter: () -> Unit
) {
    val sheet = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded, skipHiddenState = true)
    val scaffold = rememberBottomSheetScaffoldState(bottomSheetState = sheet)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var toolbarHeight by remember { mutableIntStateOf(0) }
    var stripHeight by remember { mutableIntStateOf(0) }
    var summaryHeight by remember { mutableIntStateOf(0) }
    val toolbarInset = if (toolbarHeight > 0) with(density) { toolbarHeight.toDp() } else 92.dp
    val stripInset = (if (stripHeight > 0) with(density) { stripHeight.toDp() } else 76.dp) + 8.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = maxHeight
        val landscape = maxWidth > maxHeight
        val selectedPanel: @Composable () -> Unit = {
            SelectedPersonPanel(person, point, own, canViewLocation, personSharing, members.isEmpty(), busy, onHistory, onSafety, onConnect, onEnableSharing) { summaryHeight = it }
        }
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    FamilyMap(state, modifier = Modifier.fillMaxSize(), mapState = mapState, selectedId = selectedId, recenterRequest = recenterRequest, onSelect = onSelect, bottomPadding = stripInset, topPadding = toolbarInset)
                    MapToolbar(shareLabel, sharing, busy, onShareDetails, onRefresh, onAlerts, onSettings, Modifier.onSizeChanged { toolbarHeight = it.height })
                    FamilyStrip(me, members, selectedId, onSelect, onConnect, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).onSizeChanged { stripHeight = it.height })
                }
                Surface(Modifier.width(280.dp).fillMaxHeight(), color = Color.White, shadowElevation = 8.dp) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (state == null) LoadingAccount(busy, onRefresh) else selectedPanel()
                    }
                }
            }
        } else {
            val peek = 68.dp + if (summaryHeight > 0) with(density) { summaryHeight.toDp() } else 72.dp
            val visibleSheet by remember(sheet, height, density, peek) {
                derivedStateOf { runCatching { (height - with(density) { sheet.requireOffset().toDp() }).coerceIn(peek, height) }.getOrDefault(peek) }
            }
            BottomSheetScaffold(
                scaffoldState = scaffold, modifier = Modifier.fillMaxSize(), containerColor = Canvas,
                sheetPeekHeight = peek, sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                sheetContainerColor = Color.White, sheetShadowElevation = 8.dp,
                sheetDragHandle = {
                    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Surface({ scope.launch { if (sheet.currentValue == SheetValue.Expanded) sheet.partialExpand() else sheet.expand() } }, shape = RoundedCornerShape(12.dp), modifier = Modifier.width(72.dp).height(48.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(if (sheet.currentValue == SheetValue.Expanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp, if (sheet.currentValue == SheetValue.Expanded) "가족 정보 접기" else "가족 정보 펼치기", tint = Muted)
                            }
                        }
                    }
                },
                sheetContent = {
                    Column(Modifier.fillMaxWidth().heightIn(max = (height * 0.56f).coerceAtLeast(180.dp)).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (state == null) LoadingAccount(busy, onRefresh) else selectedPanel()
                    }
                }
            ) {
                Box(Modifier.fillMaxSize()) {
                    FamilyMap(state, modifier = Modifier.fillMaxSize(), mapState = mapState, selectedId = selectedId, recenterRequest = recenterRequest, onSelect = onSelect, bottomPadding = visibleSheet + stripInset, topPadding = toolbarInset)
                    MapToolbar(shareLabel, sharing, busy, onShareDetails, onRefresh, onAlerts, onSettings, Modifier.onSizeChanged { toolbarHeight = it.height })
                    Column(Modifier.align(Alignment.BottomCenter).padding(bottom = visibleSheet + 8.dp)) {
                        // The SDK owns the right-hand zoom controls, including when the sheet is expanded.
                        FilledTonalIconButton(onRecenter, enabled = point != null, modifier = Modifier.align(Alignment.Start).padding(start = 16.dp, bottom = 32.dp)) { Icon(Icons.Rounded.MyLocation, "선택한 가족 위치로 이동") }
                        FamilyStrip(me, members, selectedId, onSelect, onConnect, Modifier.onSizeChanged { stripHeight = it.height })
                    }
                }
            }
        }
    }
}

@Composable private fun MapToolbar(label: String, sharing: Boolean, busy: Boolean, onShare: () -> Unit, onRefresh: () -> Unit, onAlerts: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 3.dp) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onShare, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("어딧", color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(label, color = if (sharing) Mint else Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onRefresh, enabled = !busy) { Icon(Icons.Rounded.Refresh, "새로고침", tint = Muted) }
            IconButton(onAlerts) { Icon(Icons.Rounded.NotificationsNone, "가족 소식", tint = Ink) }
            IconButton(onSettings) { Icon(Icons.Rounded.Settings, "설정", tint = Ink) }
        }
    }
}

@Composable private fun FamilyStrip(me: JSONObject?, members: List<JSONObject>, selectedId: String, onSelect: (String) -> Unit, onConnect: () -> Unit, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(selectedId, members.size) {
        val index = if (selectedId == me?.optString("id")) 0 else members.indexOfFirst { it.optString("id") == selectedId }.let { if (it < 0) 0 else it + if (me != null) 1 else 0 }
        list.animateScrollToItem(index)
    }
    LazyRow(modifier.fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        me?.let { item(key = it.optString("id")) { FamilyChip(it, true, selectedId == it.optString("id")) { onSelect(it.optString("id")) } } }
        items(members, key = { it.getString("id") }) { member -> FamilyChip(member, false, selectedId == member.optString("id")) { onSelect(member.optString("id")) } }
        item(key = "connect") {
            Surface(onConnect, shape = RoundedCornerShape(18.dp), color = Color.White, shadowElevation = 2.dp, modifier = Modifier.heightIn(min = 60.dp)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.PersonAdd, null, tint = Violet); Spacer(Modifier.width(8.dp)); Text("가족 연결", color = Violet, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
            }
        }
    }
}

@Composable private fun FamilyChip(person: JSONObject, own: Boolean, chosen: Boolean, onClick: () -> Unit) {
    val name = if (own) "나" else person.optString("name")
    val roleName = if (person.optString("role") == "guardian") "보호자" else "피보호자"
    val density = LocalDensity.current
    val avatarSize = with(density) { 32.sp.toDp() }
    Surface(onClick, shape = RoundedCornerShape(18.dp), color = if (chosen) Color(0xFFEEEDFF) else Color.White, border = BorderStroke(if (chosen) 2.dp else 1.dp, if (chosen) Violet else Color(0xFFE4E6EF)), shadowElevation = 2.dp,
        modifier = Modifier.heightIn(min = 60.dp).widthIn(max = 156.dp * density.fontScale.coerceAtLeast(1f)).semantics { selected = chosen; contentDescription = "$name 선택" }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(avatarSize).background(if (chosen) Violet else Canvas, CircleShape), contentAlignment = Alignment.Center) { Text(name.take(1), color = if (chosen) Color.White else Muted, fontWeight = FontWeight.Bold) }
            Column {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(when {
                    own -> if (person.optString("role") == "guardian") "보호자" else "피보호자"
                    !person.optBoolean("canViewLocation") -> "보호자 · 위치 비공개"
                    !person.optBoolean("sharing") -> "$roleName · 공유 꺼짐"
                    else -> "$roleName · 위치 공유 중"
                }, color = Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable private fun SelectedPersonPanel(
    person: JSONObject?,
    point: JSONObject?,
    own: Boolean,
    canViewLocation: Boolean,
    sharing: Boolean,
    emptyFamily: Boolean,
    busy: Boolean,
    history: () -> Unit,
    safety: () -> Unit,
    connect: () -> Unit,
    enableSharing: () -> Unit,
    onSummaryHeight: (Int) -> Unit,
) {
    val name = person?.optString("name")?.takeIf { it.isNotBlank() } ?: "내 위치"
    val role = person?.optString("role")
    val roleName = if (role == "guardian") "보호자" else "피보호자"
    val status = when {
        !canViewLocation -> "$roleName 위치는 역할상 비공개"
        !sharing -> "위치 공유 꺼짐"
        point == null -> "첫 위치 수신 대기"
        else -> "마지막 갱신 ${ageText(point.optString("recordedAt"))}"
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).onSizeChanged { onSummaryHeight(it.height) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(44.dp).background(if (sharing) Color(0xFFE7F5EF) else Canvas, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { Icon(if (point != null) Icons.Rounded.LocationOn else Icons.Rounded.LocationOff, null, tint = if (sharing) Mint else Muted) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(if (own) "$name · 나" else name, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, color = if (point != null) Mint else Muted, fontSize = 12.sp)
        }
    }
    HorizontalDivider(color = Canvas)
    if (point != null) {
        Text("${whenText(point.optString("recordedAt"))}에 확인한 위치입니다.", color = Muted, fontSize = 12.sp)
        if (!point.isNull("accuracy")) Text("위치 정확도 약 ${point.optDouble("accuracy").toInt()}m · 실제 위치와 차이가 있을 수 있어요.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
    } else {
        Text(when {
            !canViewLocation -> "피보호자는 보호자의 현재 위치와 이동 기록을 볼 수 없습니다."
            sharing -> "위치가 도착하면 지도에 표시됩니다. 권한과 기기의 네트워크 상태에 따라 지연될 수 있어요."
            own -> "공유를 켜기 전에는 이 기기의 위치를 수집하지 않습니다."
            role == "guardian" -> "이 보호자가 직접 위치 공유를 켜면 다른 보호자의 지도와 기록에 표시됩니다."
            else -> "이 피보호자가 직접 위치 공유를 켜면 역할상 볼 수 있는 가족의 지도와 기록에 표시됩니다."
        }, color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val actions: @Composable (Modifier) -> Unit = { buttonModifier ->
            OutlinedButton(history, buttonModifier.heightIn(min = 48.dp), enabled = canViewLocation && sharing && !busy) { Icon(Icons.Rounded.Route, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("위치기록") }
            OutlinedButton(if (own) safety else connect, buttonModifier.heightIn(min = 48.dp), enabled = !busy) { Icon(if (own) Icons.Rounded.Radar else Icons.Rounded.PeopleOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (own) "내 안심존" else "가족 관리") }
        }
        if (maxWidth < 320.dp || LocalDensity.current.fontScale > 1.3f) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { actions(Modifier.fillMaxWidth()) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { actions(Modifier.weight(1f)) }
        }
    }
    if (own && !sharing) Button(enableSharing, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !busy && person != null) { Text("동의하고 위치 공유 시작") }
    if (emptyFamily) {
        Text("아직 연결된 가족이 없어요. 역할별 초대 코드를 주고받아 연결해 보세요.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
        TextButton(connect, Modifier.fillMaxWidth()) { Text("가족 연결하기") }
    }
}

private fun ageText(value: String): String = runCatching {
    val minutes = Duration.between(Instant.parse(value), Instant.now()).toMinutes().coerceAtLeast(0)
    when { minutes < 1 -> "방금"; minutes < 60 -> "${minutes}분 전"; minutes < 1440 -> "${minutes / 60}시간 전"; else -> "${minutes / 1440}일 전" }
}.getOrDefault("시간 정보 없음")

@Composable private fun LoadingAccount(busy: Boolean, refresh: () -> Unit) {
    Text("가족 정보를 불러오고 있어요", fontWeight = FontWeight.Bold, fontSize = 18.sp)
    Text("연결되지 않으면 다시 불러오기를 눌러 서버 연결을 확인하세요.", color = Muted, fontSize = 13.sp)
    TextButton(refresh, enabled = !busy) { Text("다시 불러오기") }
}
