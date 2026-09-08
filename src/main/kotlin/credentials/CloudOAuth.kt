package credentials

import bitbucket.BitbucketClientFactory
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.credentialStore.Credentials
import com.intellij.ide.BrowserUtil
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.progress.ProgressManager
import org.apache.http.HttpHeaders
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.HttpPost
import org.apache.http.message.BasicNameValuePair
import util.LOG
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

/** OAuth 2.0 Authorization Code sign-in for Bitbucket Cloud.
 * docs: Bitbucket Cloud OAuth sign-in */
object CloudOAuth {

    // Shared built-in OAuth consumer, deliberately embedded rather than per-user registered.
    private const val CLIENT_ID = "TsQGRp8mnqkd0TSSduGypnuJH78P5Fm4"
    private const val CLIENT_SECRET = "ATOAvcVeHgf0w55h4JlqSX2wKgKlCFGG9z6sAhFtehHdGAJJtoIgCAL1XpQ1kikEO6IT9BE27CDB"

    // Must exactly match the consumer's registered Callback URL.
    private const val CALLBACK_PORT = 47829

    private const val AUTHORIZE_URL = "https://bitbucket.org/site/oauth2/authorize"
    private const val TOKEN_URL = "https://bitbucket.org/site/oauth2/access_token"

    // Direct PasswordSafe key, not CredentialStore - different credential shape (two tokens + expiry).
    private const val OAUTH_KEY = "bitbucket.org-oauth"

    // Refresh once fewer than this many millis remain, not right at expiry - margin for the
    // request itself and clock drift.
    private const val REFRESH_MARGIN_MILLIS = 2 * 60 * 1000L
    private const val CALLBACK_TIMEOUT_MILLIS = 5 * 60 * 1000L
    private const val POLL_TIMEOUT_MILLIS = 1000

    private val objectMapper = ObjectMapper()

    data class TokenBundle(
            @JsonProperty("accessToken") val accessToken: String,
            @JsonProperty("refreshToken") val refreshToken: String,
            @JsonProperty("expiresAt") val expiresAt: Long)

    /** Thrown for anything [authorize] should surface to the user rather than just log. */
    class AuthorizationException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Must be called off the EDT. */
    fun stored(): TokenBundle? {
        val json = PasswordSafe.instance.get(attributes())?.getPasswordAsString() ?: return null
        return try {
            objectMapper.readValue(json, TokenBundle::class.java)
        } catch (e: Exception) {
            LOG.warn("Stored Bitbucket Cloud OAuth token bundle could not be parsed, discarding", e)
            null
        }
    }

    /** Must be called off the EDT. */
    fun clear() {
        PasswordSafe.instance.set(attributes(), null)
    }

    /** Must be called off the EDT. Null means never signed in; a failed refresh also returns
     * null instead of throwing. */
    fun freshAccessToken(): String? {
        val bundle = stored() ?: return null
        if (bundle.expiresAt - System.currentTimeMillis() > REFRESH_MARGIN_MILLIS) {
            return bundle.accessToken
        }
        return try {
            val refreshed = exchangeToken(
                    BasicNameValuePair("grant_type", "refresh_token"),
                    BasicNameValuePair("refresh_token", bundle.refreshToken))
            store(refreshed)
            refreshed.accessToken
        } catch (e: Exception) {
            LOG.warn("Bitbucket Cloud OAuth token refresh failed, clearing stored credentials", e)
            clear()
            null
        }
    }

    /** Must be called off the EDT - opens the system browser and blocks on a local loopback
     * listener for the redirect. Caller should wrap this in a cancellable modal progress. Throws
     * [AuthorizationException] on denial, timeout, a port conflict, or a malformed response. */
    fun authorize(): TokenBundle {
        val state = UUID.randomUUID().toString()
        val socket = try {
            ServerSocket(CALLBACK_PORT, 1, InetAddress.getLoopbackAddress())
        } catch (e: Exception) {
            throw AuthorizationException("Could not start the local sign-in listener on port " +
                    "$CALLBACK_PORT - something else on this machine may already be using it.", e)
        }
        socket.use {
            it.soTimeout = POLL_TIMEOUT_MILLIS
            BrowserUtil.browse("$AUTHORIZE_URL?client_id=$CLIENT_ID&response_type=code&state=$state")
            val code = awaitCallback(it, state)
            // Deliberately not persisted here - caller commits via store() on its own timing.
            return exchangeToken(
                    BasicNameValuePair("grant_type", "authorization_code"),
                    BasicNameValuePair("code", code))
        }
    }

    /** Persists [bundle] as the current credential. Must be called off the EDT. */
    fun store(bundle: TokenBundle) {
        PasswordSafe.instance.set(attributes(), Credentials("oauth", objectMapper.writeValueAsString(bundle)))
    }

    private fun attributes() = CredentialStore.attributesFor(OAUTH_KEY)

    /** Accepts exactly one connection, reads its request line, replies with a small fixed HTML
     * page. Hand-rolled minimal parse instead of `com.sun.net.httpserver.HttpServer`. */
    private fun awaitCallback(socket: ServerSocket, expectedState: String): String {
        val connection = acceptWithCancellation(socket, System.currentTimeMillis() + CALLBACK_TIMEOUT_MILLIS)
        connection.use { conn ->
            val requestLine = conn.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine() ?: ""
            val params = parseCallbackParams(requestLine)
            val out = conn.getOutputStream()
            out.write(CALLBACK_RESPONSE.toByteArray(StandardCharsets.UTF_8))
            out.flush()
            val error = params["error"]
            if (error != null) {
                throw AuthorizationException(
                        if (error == "access_denied") "Sign-in was cancelled."
                        else "Bitbucket returned an error during sign-in: $error")
            }
            if (params["state"] != expectedState) {
                throw AuthorizationException("Sign-in response failed a security check - try again.")
            }
            return params["code"]
                    ?: throw AuthorizationException("Bitbucket's sign-in response was missing a code.")
        }
    }

    // Short bursts, not one blocking accept() - so Cancel is actually noticed.
    private fun acceptWithCancellation(socket: ServerSocket, deadlineMillis: Long): Socket {
        while (true) {
            // Must propagate unmodified, not become an AuthorizationException - swallowing it
            // here leaves the platform's own progress/modal bookkeeping thinking the task is
            // still running, which is what left the "Reconfigure" dialog looking alive but inert
            // after Cancel. docs: Known incidents
            ProgressManager.checkCanceled()
            if (System.currentTimeMillis() > deadlineMillis) {
                throw AuthorizationException("Sign-in timed out waiting for the browser - try again.")
            }
            try {
                return socket.accept()
            } catch (e: SocketTimeoutException) {
                // Just poll again - see the class-level timeout constants.
            }
        }
    }

    /** Parses the query string out of an HTTP request line's path, e.g.
     * "GET /callback?code=...&state=... HTTP/1.1". */
    private fun parseCallbackParams(requestLine: String): Map<String, String> {
        val path = requestLine.split(' ').getOrNull(1) ?: return emptyMap()
        val query = path.substringAfter('?', "")
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) null
            else URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8) to
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)
        }.toMap()
    }

    private fun exchangeToken(vararg params: BasicNameValuePair): TokenBundle {
        val post = HttpPost(TOKEN_URL)
        val authArray = "$CLIENT_ID:$CLIENT_SECRET".toByteArray(StandardCharsets.UTF_8)
        post.setHeader(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(authArray))
        post.entity = UrlEncodedFormEntity(params.toList())
        val response = BitbucketClientFactory.createHttpClient().execute(post)
        val body = response.entity.content.bufferedReader(StandardCharsets.UTF_8).readText()
        if (response.statusLine.statusCode != 200) {
            throw AuthorizationException(
                    "Bitbucket rejected the sign-in request (${response.statusLine.statusCode}).")
        }
        val json = objectMapper.readTree(body)
        val accessToken = json.path("access_token").asText("")
        val refreshToken = json.path("refresh_token").asText("")
        if (accessToken.isBlank() || refreshToken.isBlank()) {
            throw AuthorizationException("Bitbucket's token response was missing a token.")
        }
        val expiresInSeconds = json.path("expires_in").asLong(3600L)
        return TokenBundle(accessToken, refreshToken, System.currentTimeMillis() + expiresInSeconds * 1000)
    }

    private const val CALLBACK_RESPONSE = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Connection: close\r\n\r\n" +
            "<html><body style=\"font-family:sans-serif;text-align:center;margin-top:15%\">" +
            "<p>Signed in to Bitbucket. You can close this tab and return to your IDE.</p>" +
            "</body></html>"
}
