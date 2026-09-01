package http

import org.apache.http.HttpHeaders
import org.apache.http.HttpMessage

class AccessTokenRequestFactory(private val token: String): RequestFactory() {
    override fun addAuthHeader(request: HttpMessage) {
        request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }
}