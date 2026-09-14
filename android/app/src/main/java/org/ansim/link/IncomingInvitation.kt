package org.ansim.link

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

internal data class IncomingInvitation(
    val id: String,
    val server: String,
    val code: String,
) {
    fun toSaved(): String = JSONObject().put("id", id).put("server", server)
        .put("code", code).toString()

    companion object {
        private val codePattern = Regex("[A-Za-z0-9_-]{16}")
        fun validCode(code: String) = codePattern.matches(code)

        fun origin(value: String): String {
            require(value.length <= 2048 && value == value.trim() && value.none { it.isWhitespace() || it.isISOControl() })
            val uri = URI(SessionStore.normalizeBaseUrl(value))
            val port = if ((uri.scheme == "https" && uri.port == 443) || (uri.scheme == "http" && uri.port == 80)) -1 else uri.port
            return URI(uri.scheme, null, uri.host, port, null, null, null).toASCIIString()
        }
        fun manual(server: String, code: String): IncomingInvitation {
            require(validCode(code.trim())) { "초대 코드는 영문·숫자·밑줄·하이픈으로 된 16자리입니다." }
            return IncomingInvitation(UUID.randomUUID().toString(), origin(server.trim()), code.trim())
        }


        fun parse(value: String): IncomingInvitation {
            require(value.length <= 4096) { "초대 링크가 너무 깁니다." }
            val uri = URI(value.trim())
            if (uri.scheme == "https") {
                require(uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null) {
                    "초대 링크에 불필요한 주소 정보가 있습니다. 가족이 보낸 원래 링크를 입력해 주세요."
                }
                val path = uri.rawPath.orEmpty()
                require(path.startsWith("/invite/")) { "가족이 보낸 /invite/ 초대 링크를 입력해 주세요." }
                return manual("${uri.scheme}://${uri.rawAuthority}", path.removePrefix("/invite/"))
            }
            require(uri.scheme == "ansimlink" && uri.rawAuthority == "invite" && uri.rawPath.isNullOrEmpty() && uri.rawFragment == null)
            val pairs = (uri.rawQuery ?: error("Missing invitation parameters")).split('&')
            require(pairs.size == 2)
            val params = pairs.associate { part ->
                val split = part.indexOf('=')
                require(split > 0 && part.indexOf('=', split + 1) < 0 && !part.contains('+'))
                val name = part.substring(0, split)
                require(name == "server" || name == "code")
                name to URLDecoder.decode(part.substring(split + 1), "UTF-8")
            }
            require(params.size == 2)
            val server = origin(params.getValue("server"))
            val code = params.getValue("code")
            require(validCode(code))
            return IncomingInvitation(UUID.randomUUID().toString(), server, code)
        }

        fun fromSaved(value: String): IncomingInvitation {
            val json = JSONObject(value)
            val code = json.getString("code")
            require(validCode(code))
            return IncomingInvitation(json.getString("id"), origin(json.getString("server")), code)
        }
    }
}

internal data class InvitationPreview(val inviterName: String?, val isSetup: Boolean, val role: String, val expiresAt: String)
