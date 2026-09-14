package org.ansim.link

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant

@Composable
internal fun IncomingInvitationScreen(
    invitation: IncomingInvitation,
    hasSession: Boolean,
    busy: Boolean,
    acceptanceError: String?,
    load: suspend () -> InvitationPreview,
    join: (String) -> Unit,
    connect: () -> Unit,
    cancel: () -> Unit,
) {
    var preview by remember { mutableStateOf<InvitationPreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    var expired by remember { mutableStateOf(false) }
    var name by rememberSaveable(invitation.id) { mutableStateOf("") }
    LaunchedEffect(attempt) {
        loading = true
        error = null
        preview = null
        expired = false
        try { preview = load() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = invitationErrorText(e) }
        finally { loading = false }
    }
    LaunchedEffect(preview) {
        val value = preview ?: return@LaunchedEffect
        val expiry = Instant.parse(value.expiresAt)
        while (true) {
            expired = !Instant.now().isBefore(expiry)
            if (expired) break
            delay(1000)
        }
    }
    Surface(Modifier.fillMaxSize(), color = Canvas) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("가족 연결 초대", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("초대 서버", color = Muted)
            Text(invitation.server, fontWeight = FontWeight.Bold)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            preview?.let { value ->
                val roleName = if (value.role == "guardian") "보호자" else "피보호자"
                SafetyCard {
                    Text(if (value.isSetup) "가족의 첫 기기 연결" else "${value.inviterName}님의 초대", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("초대 역할 · $roleName", color = Violet, fontWeight = FontWeight.Bold)
                    Text("유효 시간: ${whenText(value.expiresAt)}", color = Muted)
                    if (hasSession) {
                        Text("현재 프로필의 역할이 $roleName 역할이면 이 가족과 연결할 수 있습니다.")
                        InfoStrip("초대를 수락하면 두 가족 그룹이 합쳐집니다. 보호자는 그룹의 공유 위치를 모두 볼 수 있고, 피보호자는 보호자 위치를 볼 수 없습니다.", Violet)
                    } else {
                        Text(if (value.isSetup) "서버 운영자가 발급한 첫 기기용 보호자 초대입니다. 연결한 뒤 역할별로 가족을 초대할 수 있습니다." else "초대한 가족의 그룹에 연결하고 이 기기에서 사용할 이름을 정합니다.")
                        OutlinedTextField(name, { name = it }, enabled = !busy, label = { Text("가족에게 보일 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("아래 버튼을 누르면 이 서버를 신뢰하고 $roleName 역할로 참여하는 데 동의합니다. 서버 운영자가 이름과 가족 연결 정보를 처리합니다.", color = Muted, fontSize = 13.sp)
                        InfoStrip(if (value.role == "protected") "위치 공유는 꺼진 상태로 시작하며, 가족 그룹에 공유하려면 나중에 별도로 동의해야 합니다." else "위치 공유는 꺼진 상태로 시작합니다. 공유하면 가족 그룹의 보호자만 볼 수 있습니다.", Violet)
                    }
                }
            }
            val message = acceptanceError ?: error ?: if (expired) "초대가 만료되었습니다. 초대한 가족에게 새 초대를 요청해 주세요." else null
            if (message != null) InfoStrip(message, Danger)
            if (message != null) {
                Text(if (hasSession) "현재 프로필과 기존 가족 그룹은 그대로 유지됩니다. 초대를 닫으면 가족 화면으로 돌아갑니다." else "초대와 연결 상태를 다시 확인해 주세요. 새 초대가 필요하면 가족이나 서버 운영자에게 요청해 주세요.", color = Muted, fontSize = 13.sp)
                OutlinedButton({ attempt++ }, enabled = !loading && !busy, modifier = Modifier.fillMaxWidth()) { Text("초대 다시 확인") }
            }
            Button({ if (hasSession) connect() else join(name.trim()) }, enabled = preview != null && !expired && !loading && !busy && error == null && (!hasSession || preview?.isSetup == false) && (hasSession || name.trim().isNotEmpty()), modifier = Modifier.fillMaxWidth()) {
                Text(if (hasSession) "같은 역할로 가족 연결하기" else "${if (preview?.role == "guardian") "보호자" else "피보호자"} 역할로 연결하기")
            }
            TextButton(cancel, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (hasSession) "초대 닫기 · 현재 프로필 유지" else "다른 초대 입력하기") }
        }
    }
}

internal fun invitationErrorText(error: Exception): String = when {
    error is ApiException && error.status == 404 -> "초대가 만료되었거나 이미 사용되었습니다. 초대한 가족에게 새 초대를 요청해 주세요."
    error is IllegalArgumentException -> "초대 정보가 올바르지 않습니다. 서버 주소와 16자리 코드를 확인하거나 가족에게 원래 초대 링크를 요청해 주세요."
    else -> error.message ?: "초대를 확인하지 못했습니다. 연결 상태를 확인하고 다시 시도해 주세요."
}
