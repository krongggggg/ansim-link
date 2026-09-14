package org.ansim.link

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.net.URI

private enum class SettingsDetail { Guardian, Inactivity, Data, Reinstall, App, Server, Delete }

@Composable
internal fun SettingsScreen(me: JSONObject?, busy: Boolean, monitoring: Boolean, monitor: (Boolean) -> Unit, mutate: (String, String, JSONObject?) -> Unit, delete: (String) -> Unit, server: String) {
    val currentName = me?.optString("name").orEmpty()
    val currentMinutes = me?.optInt("inactivityMinutes", 720)
    val canMutate = !busy && me != null
    var detail by rememberSaveable(me?.optString("id")) { mutableStateOf<SettingsDetail?>(null) }
    var minutesInput by rememberSaveable { mutableStateOf("") }
    var confirmName by rememberSaveable(currentName) { mutableStateOf("") }
    val serverOrigin = remember(server) {
        runCatching {
            val uri = URI(server)
            if (uri.scheme !in listOf("https", "http") || uri.host.isNullOrBlank()) null
            else URI(uri.scheme, null, uri.host, uri.port, null, null, null).toASCIIString()
        }.getOrNull() ?: "서버 주소를 확인할 수 없습니다"
    }

    SafetyCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Person, null, tint = Violet, modifier = Modifier.size(32.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(currentName.ifBlank { "내 프로필" }, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(if (me == null) "프로필을 불러오는 중" else "이 기기에 연결된 프로필", color = Muted, fontSize = 12.sp)
            }
        }
    }

    SafetyCard {
        SettingsSectionTitle("알림")
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .toggleable(value = monitoring, enabled = canMutate, role = Role.Switch, onValueChange = monitor),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("백그라운드 보호자 알림", color = Ink, fontWeight = FontWeight.SemiBold)
                Text("내 위치 공유 없이 · 15분 이상 간격", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
            Switch(checked = monitoring, onCheckedChange = null, enabled = canMutate)
        }
        SettingsRow(Icons.Rounded.Info, "알림 작동 방식", "위치 수집 중에는 이 스위치와 별도로 확인") { detail = SettingsDetail.Guardian }
        HorizontalDivider(color = Muted.copy(alpha = 0.12f))
        SettingsRow(Icons.Rounded.Schedule, "움직임 없음 알림", currentMinutes?.let { "현재 ${inactivityLabel(it)}" } ?: "프로필을 불러오는 중", enabled = canMutate) {
            minutesInput = currentMinutes?.toString().orEmpty()
            detail = SettingsDetail.Inactivity
        }
    }

    SafetyCard {
        SettingsSectionTitle("데이터 관리")
        SettingsRow(Icons.Rounded.PrivacyTip, "보관과 삭제", "위치 기록 7일 · 공유를 끄면 위치 기록 삭제") { detail = SettingsDetail.Data }
        HorizontalDivider(color = Muted.copy(alpha = 0.12f))
        SettingsRow(Icons.Rounded.PhonelinkErase, "재설치·데이터 초기화 안내", "다시 연결하려면 새 초대가 필요해요") { detail = SettingsDetail.Reinstall }
    }

    SafetyCard {
        SettingsSectionTitle("앱 정보")
        SettingsRow(Icons.Rounded.Info, "어딧 ${BuildConfig.VERSION_NAME}", "독립 오픈소스 · 라이선스와 지도 안내") { detail = SettingsDetail.App }
        HorizontalDivider(color = Muted.copy(alpha = 0.12f))
        SettingsRow(Icons.Rounded.Dns, "연결된 서버", "초대로 연결된 서버 주소 확인 · 읽기 전용") { detail = SettingsDetail.Server }
    }

    SafetyCard {
        Text("프로필 삭제", color = Danger, fontWeight = FontWeight.Bold)
        Text("내 데이터 영구 삭제 · 이 기기 연결 종료", color = Muted, fontSize = 12.sp)
        OutlinedButton(
            onClick = { confirmName = ""; detail = SettingsDetail.Delete },
            enabled = canMutate && currentName.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)
        ) {
            Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("내 프로필 삭제")
        }
    }

    when (detail) {
        SettingsDetail.Guardian -> SettingsInfoDialog("알림 작동 방식", { detail = null }) {
            SettingsParagraph("백그라운드 보호자 알림", "내 위치를 공유하지 않아도 가족 소식을 확인합니다. Android 예약 작업으로 15분 이상 간격으로 확인하며, 정확히 15분마다 실행되는 것은 아닙니다.")
            SettingsParagraph("전체 알림을 끄는 스위치가 아니에요", "위치 수집이 실행되는 동안에는 이 스위치와 관계없이 약 15초마다 가족 소식을 확인합니다. 앱이 열려 있는 동안에도 약 15초마다 갱신됩니다.")
            SettingsParagraph("지연되거나 전달되지 않을 수 있어요", "강제 종료, 배터리 절약, 통신 장애, Android 알림 권한 설정에 영향을 받습니다. 긴급 구조기관에 신고하지 않으며, 위급할 때는 112·119에 직접 연락하세요.")
        }
        SettingsDetail.Inactivity -> {
            val enteredMinutes = minutesInput.trim().toIntOrNull()
            val valid = enteredMinutes != null && enteredMinutes in 60..4320
            val changed = enteredMinutes != currentMinutes
            AlertDialog(
                onDismissRequest = { detail = null },
                title = { Text("움직임 없음 알림") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(currentMinutes?.let { "현재 기준: ${inactivityLabel(it)} (${it}분)" } ?: "프로필을 불러오는 중", color = Ink, fontWeight = FontWeight.SemiBold)
                        Text("위치가 정상 수신되는 동안 이 시간만큼 이동이 없으면 알립니다. 위치 수신 중단은 별도의 연결 끊김 알림으로 구분합니다. 의료·낙상 감지 기능이 아닙니다.", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
                        OutlinedTextField(
                            value = minutesInput,
                            onValueChange = { minutesInput = it },
                            enabled = canMutate,
                            label = { Text("알림 기준 · 분") },
                            supportingText = { Text(if (valid) "60~4320분 (1~72시간), 1분 단위" else "60~4320 사이의 정수 분을 입력해 주세요.") },
                            isError = !valid,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("저장을 눌러야 변경됩니다.", color = Muted, fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (canMutate && valid && changed) {
                            mutate("PATCH", "/api/me", json("inactivityMinutes" to enteredMinutes))
                            detail = null
                        }
                    }, enabled = canMutate && valid && changed) { Text("저장") }
                },
                dismissButton = { TextButton({ detail = null }) { Text("취소") } }
            )
        }
        SettingsDetail.Data -> SettingsInfoDialog("데이터 보관과 삭제", { detail = null }) {
            SettingsParagraph("위치 기록", "서버에서 최대 7일 보관합니다. 위치 공유를 끄면 서버에 저장된 내 위치 기록이 삭제됩니다.")
            SettingsParagraph("이전 버전의 긴급 요청", "이전 버전에서 생성한 긴급 요청(SOS)과 첨부 자료는 요청이 종료될 때까지 유지되며, 종료 후 7일 보관됩니다. 위치 공유를 끄는 것과는 별개의 보관 기준입니다.")
            SettingsParagraph("프로필 삭제", "내 프로필과 연결된 자료를 서버에서 영구 삭제하고 이 기기의 연결을 종료합니다. 앱만 삭제하면 서버 데이터는 삭제되지 않습니다.")
            SettingsParagraph("서버 백업의 범위", "위 보관·삭제 기준은 운영 중인 서버 데이터에 적용됩니다. 운영자가 별도로 만든 백업 사본은 앱에서 삭제하지 않습니다. 백업에 포함되는 자료, 보관 기간과 삭제 방법은 서버 운영자에게 확인해 주세요.")
        }
        SettingsDetail.Reinstall -> SettingsInfoDialog("재설치·데이터 초기화", { detail = null }) {
            SettingsParagraph("새 초대가 필요해요", "앱을 재설치하거나 앱 데이터를 지우면 이 기기의 연결 정보도 사라집니다. 다시 사용하려면 새 초대가 필요하며, 이전 프로필은 자동 복구되지 않습니다.")
            SettingsParagraph("앱 삭제와 프로필 삭제는 달라요", "앱을 삭제하거나 데이터를 초기화하는 것만으로 서버의 프로필과 자료가 삭제되지는 않습니다. 내 서버 자료를 지우려면 연결된 상태에서 ‘내 프로필 삭제’를 이용하세요.")
        }
        SettingsDetail.App -> SettingsInfoDialog("어딧 정보", { detail = null }) {
            SettingsParagraph("버전 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", "어딧의 앱·서버 자체 소스는 MIT License로 공개됩니다. 도와줘 앱과 관련이 없으며 전용 링·카카오톡 알림은 지원하지 않습니다.")
            SettingsParagraph("NAVER Maps", "네이버 지도 SDK에는 네이버 클라우드의 별도 이용약관이 적용됩니다. 지도 요청 시 제공자에게 IP 주소와 조회 지역이 전달됩니다. SDK 법적 공지와 이용약관 링크는 지도 화면의 NAVER 로고를 눌러 확인할 수 있습니다.")
            SettingsParagraph("오픈소스 라이브러리", "AndroidX · Kotlin: Apache License 2.0\nZXing (QR 코드): Apache License 2.0\n각 라이브러리의 저작권과 라이선스는 해당 권리자에게 있습니다.")
        }
        SettingsDetail.Server -> SettingsInfoDialog("연결된 서버", { detail = null }) {
            SettingsParagraph("서버 주소 · 읽기 전용", serverOrigin)
            Text("가족 초대로 연결된 서버입니다. 이 화면에서는 주소를 변경하지 않습니다. 서버 운영과 데이터·백업 정책은 초대한 가족 또는 운영자에게 확인해 주세요.", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
        }
        SettingsDetail.Delete -> AlertDialog(
            onDismissRequest = { detail = null; confirmName = "" },
            title = { Text("프로필과 데이터를 삭제할까요?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("가족 연결과 위치 기록을 포함해 내 프로필에 저장된 자료를 영구 삭제하고 이 기기의 연결을 종료합니다. 되돌릴 수 없습니다.")
                    Text("다시 사용하려면 새 초대가 필요합니다. 앱 재설치·데이터 초기화 후에도 새 초대가 필요하며, 이전 프로필은 자동 복구되지 않습니다.", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
                    Text("삭제하려면 현재 표시 이름 ‘$currentName’을 정확히 입력해 주세요.")
                    OutlinedTextField(confirmName, { confirmName = it }, enabled = canMutate, label = { Text("현재 표시 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (canMutate && currentName.isNotBlank() && confirmName == currentName) {
                        delete(confirmName)
                        detail = null
                        confirmName = ""
                    }
                }, enabled = canMutate && currentName.isNotBlank() && confirmName == currentName) { Text("영구 삭제", color = Danger) }
            },
            dismissButton = { TextButton({ detail = null; confirmName = "" }) { Text("취소") } }
        )
        null -> Unit
    }
}

private fun inactivityLabel(minutes: Int): String = when {
    minutes % 60 == 0 -> "${minutes / 60}시간"
    else -> "${minutes / 60}시간 ${minutes % 60}분"
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, summary: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = if (enabled) Violet else Muted, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = if (enabled) Ink else Muted, fontWeight = FontWeight.SemiBold)
            Text(summary, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SettingsInfoDialog(title: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp), content = content) },
        confirmButton = { TextButton(dismiss) { Text("닫기") } }
    )
}

@Composable
private fun SettingsParagraph(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Ink, fontWeight = FontWeight.SemiBold)
        Text(body, color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
    }
}
