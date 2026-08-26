package bitbucket

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import http.AccessTokenRequestFactory
import http.BasicAuthRequestFactory
import http.RequestFactory
import org.apache.http.client.HttpClient
import org.apache.http.impl.client.HttpClients
import ui.Storer
import ui.getStorerService

object BitbucketClientFactory {

    var password: CharArray = kotlin.CharArray(0)

    // Computed, not a stored val: platform forbids requesting a service from a class initializer.
    private val storer: Storer
        get() = getStorerService()

    fun createClient(listener: ClientListener = object : ClientListener {}): BitbucketClient {
        val objectMapper = ObjectMapper()
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        return BitbucketClient(
                createHttpClient(),
                createRequestFactory(),
                storer.settings, objectMapper.reader(), objectMapper.writer(), listener)
    }

    fun createRequestFactory(): RequestFactory {
        val settings = storer.settings
        return if (settings.useAccessTokenAuth) {
            AccessTokenRequestFactory(settings.accessToken)
        } else {
            BasicAuthRequestFactory(settings.login, String(password))
        }
    }

    // "System" client reuses the IDE's own SSLContext/TrustManager.
    fun createHttpClient(): HttpClient = HttpClients.createSystem()
}
