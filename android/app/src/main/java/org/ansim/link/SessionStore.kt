package org.ansim.link

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionStore(context: Context) {
    internal val context: Context = context.applicationContext
    internal val preferences: SharedPreferences = this.context.getSharedPreferences("session", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = preferences.getString("baseUrl", "") ?: ""
        set(value) { synchronized(lock) { save(preferences.edit().putString("baseUrl", normalizeBaseUrl(value))) } }
    var token: String
        get() = decrypt(preferences.getString("token", null))
        set(value) { synchronized(lock) { save(preferences.edit().putString("token", encrypt(value))) } }
    var userId: String
        get() = preferences.getString("userId", "") ?: ""
        set(value) { synchronized(lock) { save(preferences.edit().putString("userId", value)) } }
    var sharingEnabled: Boolean
        get() = preferences.getBoolean("sharingEnabled", false) && !revokePending
        set(value) { synchronized(lock) {
            check(!value || !revokePending) { "이전 위치 공유 중지를 서버에서 확인한 뒤 다시 시작해 주세요." }
            save(preferences.edit().putBoolean("sharingEnabled", value))
        } }
    var monitoringEnabled: Boolean
        get() = preferences.getBoolean("monitoringEnabled", false)
        set(value) { synchronized(lock) { save(preferences.edit().putBoolean("monitoringEnabled", value)) } }
    var lastEventId: Long
        get() = preferences.getLong("lastEventId", 0L)
        set(value) { synchronized(lock) { save(preferences.edit().putLong("lastEventId", value)) } }
    var revokePending: Boolean
        get() = preferences.getBoolean("revokePending", false)
        set(value) {
            if (value) markRevokePending() else synchronized(lock) {
                save(preferences.edit().putBoolean("revokePending", false)
                    .remove("revokeToken").remove("revokeBaseUrl").remove("revokeUserId"))
            }
        }
    internal var eventsInitialized: Boolean
        get() = preferences.getBoolean("eventsInitialized", false)
        set(value) { synchronized(lock) { save(preferences.edit().putBoolean("eventsInitialized", value)) } }

    internal fun pendingInvitation(): IncomingInvitation? = synchronized(lock) {
        val saved = preferences.getString("pendingInvitation", null) ?: return@synchronized null
        runCatching { IncomingInvitation.fromSaved(saved) }.getOrNull()
    }

    internal fun saveInvitation(invitation: IncomingInvitation) = synchronized(lock) {
        save(preferences.edit().putString("pendingInvitation", invitation.toSaved()))
    }

    internal fun saveJoinedSession(invitation: IncomingInvitation, credential: String, identity: String) = synchronized(lock) {
        check(token.isEmpty()) { "이미 연결된 프로필이 있어 새 프로필로 바꾸지 않았습니다." }
        require(credential.isNotBlank() && identity.isNotBlank()) { "서버의 프로필 응답이 올바르지 않습니다." }
        val editor = preferences.edit()
            .putString("baseUrl", normalizeBaseUrl(invitation.server))
            .putString("token", encrypt(credential)).putString("userId", identity)
            .putBoolean("sharingEnabled", false)
        if (pendingInvitation()?.id == invitation.id) editor.remove("pendingInvitation")
        save(editor)
    }

    internal fun clearInvitation(id: String): Boolean = synchronized(lock) {
        if (pendingInvitation()?.id != id) return@synchronized false
        save(preferences.edit().remove("pendingInvitation"))
        true
    }

    internal fun markRevokePending() {
        synchronized(lock) {
            val editor = preferences.edit().putBoolean("sharingEnabled", false)
            if (!revokePending && (token.isNotEmpty() || preferences.getBoolean("sharingEnabled", false))) {
                editor.putBoolean("revokePending", true)
                    .putString("revokeToken", encrypt(token)).putString("revokeBaseUrl", baseUrl)
                    .putString("revokeUserId", userId)
            }
            save(editor)
        }
        LocationTransmission.cancel()
        if (revokePending) RevokeWorker.enqueue(context)
    }

    internal fun revokeCredentials(): Pair<String, String> = synchronized(lock) {
        val savedUser = preferences.getString("revokeUserId", "")
        val savedBase = preferences.getString("revokeBaseUrl", "") ?: ""
        val credential = if (savedUser == userId && savedBase == baseUrl && token.isNotEmpty()) token
            else decrypt(preferences.getString("revokeToken", null))
        savedBase to credential
    }

    fun clear() {
        // Preserve a separately encrypted revoke credential until the server confirms the stop.
        if (sharingEnabled) markRevokePending()
        synchronized(lock) {
            save(preferences.edit().remove("token").remove("userId")
                .putBoolean("sharingEnabled", false).putBoolean("monitoringEnabled", false)
                .remove("lastEventId").remove("eventsInitialized"))
        }
        LocationTransmission.cancel()
        context.stopService(android.content.Intent(context, SafetyService::class.java))
        AlertWorker.cancel(context)
        SafetyNotifications.clear(context)
        if (revokePending) RevokeWorker.enqueue(context)
    }

    private fun save(editor: SharedPreferences.Editor) {
        check(editor.commit()) { "안전 설정을 저장하지 못했습니다. 다시 시도해 주세요." }
    }

    private fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        return try {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            if (bytes.size < 29) return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    private fun key(): SecretKey = synchronized(lock) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "ansim.session.v1"
        private val lock = Any()
        fun normalizeBaseUrl(value: String): String {
            val uri = try { URI(value.trim()) } catch (_: Exception) { throw IllegalArgumentException("서버 주소가 올바르지 않습니다.") }
            require(uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http")) { "HTTPS 서버 주소를 입력해 주세요." }
            require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") && uri.port in -1..65535 && uri.port != 0) {
                "서버 주소에는 호스트와 포트만 입력해 주세요."
            }
            return URI(uri.scheme, null, uri.host.lowercase(), uri.port, null, null, null).toASCIIString()
        }
    }
}
