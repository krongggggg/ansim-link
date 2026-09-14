package org.ansim.link

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

class ApiException(val status: Int, message: String) : Exception(message)

internal object LocationTransmission {
    val mutex = Mutex()
    @Volatile var connection: HttpURLConnection? = null
    fun cancel() { connection?.disconnect() }
}

class ApiClient(private val store: SessionStore) {
    suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        if (path == "/api/locations") LocationTransmission.mutex.withLock {
            if (!store.sharingEnabled || store.revokePending || store.token.isEmpty() || !SafetyService.hasLocationPermission(store.context)) {
                throw ApiException(409, "위치 공유가 중지되어 위치를 보내지 않았습니다.")
            }
            json(method, path, body, store.baseUrl, store.token, location = true)
        } else json(method, path, body, store.baseUrl, store.token)
    }

    internal suspend fun previewInvitation(server: String, code: String): JSONObject = withContext(Dispatchers.IO) {
        require(IncomingInvitation.validCode(code))
        json("GET", "/api/invites/$code", null, server, "")
    }

    internal suspend fun joinInvitation(server: String, code: String, name: String): JSONObject = withContext(Dispatchers.IO) {
        check(store.token.isEmpty()) { "이미 연결된 프로필이 있어 새 프로필로 바꾸지 않았습니다." }
        require(IncomingInvitation.validCode(code))
        json("POST", "/api/invites/join", JSONObject().put("code", code).put("name", name), server, "")
    }

    suspend fun revokeSharing() = withContext(Dispatchers.IO) {
        store.markRevokePending()
        store.context.stopService(android.content.Intent(store.context, SafetyService::class.java))
        if (!store.revokePending) return@withContext
        LocationTransmission.mutex.withLock {
            if (!store.revokePending) return@withLock
            val (base, credential) = store.revokeCredentials()
            if (credential.isEmpty()) throw ApiException(401, "기기의 연결 정보가 없어 서버 공유 중지를 확인하지 못했습니다. 서버 운영자에게 기존 프로필의 공유 중지를 요청해 주세요.")
            json("PATCH", "/api/me", JSONObject().put("sharing", false), base, credential)
            store.revokePending = false
            SafetyNotifications.revokeStatus(store.context, false)
        }
    }


    private suspend fun json(method: String, path: String, body: JSONObject?, base: String, token: String, location: Boolean = false): JSONObject {
        val (bytes, mime) = exchange(method, path, body, base, token, 8 * 1024 * 1024, location)
        if (mime != "application/json") throw ApiException(0, "서버 응답 형식을 확인해 주세요.")
        return try { JSONObject(String(bytes, Charsets.UTF_8)) }
        catch (_: Exception) { throw ApiException(0, "서버 응답을 읽지 못했습니다.") }
    }

    private suspend fun exchange(method: String, path: String, body: JSONObject?, base: String, token: String, limit: Int, location: Boolean = false): Pair<ByteArray, String> {
        currentCoroutineContext().ensureActive()
        val connection = try {
            val root = SessionStore.normalizeBaseUrl(base)
            val relative = URI(path)
            require(path.startsWith("/api/") && !relative.isAbsolute && relative.rawAuthority == null && relative.rawFragment == null)
            require(!path.contains('\\') && !path.contains('\r') && !path.contains('\n'))
            require(method in setOf("GET", "POST", "PATCH", "DELETE"))
            require(!token.contains('\r') && !token.contains('\n'))
            (URI(root + path).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept", "application/json, image/jpeg, audio/mp4")
                if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
        } catch (_: Exception) { throw ApiException(0, "서버 주소 또는 요청 설정을 확인해 주세요.") }
        try {
            if (location) {
                LocationTransmission.connection = connection
                if (!store.sharingEnabled || store.revokePending || !SafetyService.hasLocationPermission(store.context)) throw ApiException(409, "위치 공유가 중지되었습니다.")
            }
            if (body != null) {
                val data = body.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(data.size)
                currentCoroutineContext().ensureActive()
                connection.outputStream.use { it.write(data) }
            }
            val status = connection.responseCode
            currentCoroutineContext().ensureActive()
            val success = status in 200..299
            val maxBytes = if (success) limit else 16 * 1024
            val stream = if (success) connection.inputStream else connection.errorStream
            val bytes = if (stream == null) byteArrayOf() else stream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > maxBytes) throw ApiException(status, "서버 응답 크기가 허용 범위를 넘었습니다.")
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            if (!success) {
                val safe = when (status) {
                    401 -> "이 기기의 연결이 유효하지 않습니다. 가족에게 새 초대를 요청해 주세요."
                    403 -> if (method == "DELETE" && path == "/api/me") "현재 표시 이름과 일치하지 않습니다. 이름을 정확히 입력해 주세요." else "권한이 없거나 위치 공유가 중지되었습니다."
                    404 -> "요청한 항목을 찾을 수 없습니다."
                    409 -> "현재 상태에서는 요청을 처리할 수 없습니다. 새로고침해 주세요."
                    413 -> "첨부 파일이 너무 큽니다."
                    429 -> "요청이 많습니다. 잠시 후 다시 시도해 주세요."
                    in 500..599 -> "서버에 연결했지만 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."
                    else -> "요청을 처리하지 못했습니다. 입력 내용을 확인해 주세요."
                }
                throw ApiException(status, safe)
            }
            return bytes to (connection.contentType ?: "").substringBefore(';').trim().lowercase()
        } catch (error: CancellationException) { throw error }
        catch (error: ApiException) { throw error }
        catch (_: Exception) { throw ApiException(0, "서버에 연결하지 못했습니다. 네트워크와 서버 주소를 확인해 주세요. Tailscale 주소는 가족 폰의 Tailscale 연결이 필요합니다.") }
        finally {
            if (location && LocationTransmission.connection === connection) LocationTransmission.connection = null
            connection.disconnect()
        }
    }
}
