package bitbucket

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.project.Project
import http.AccessTokenRequestFactory
import http.BasicAuthRequestFactory
import http.RequestFactory
import org.apache.http.client.HttpClient
import org.apache.http.impl.client.HttpClients
import ui.Settings
import ui.Storer

object BitbucketClientFactory {

    // Basic Auth's password field: shared across projects, unlike everything else here — left as
    // a global since Basic Auth itself is unreachable from the UI (see CLAUDE.md "Basic Auth").
    var password: CharArray = CharArray(0)

    fun createClient(project: Project, listener: ClientListener = object : ClientListener {}): BitbucketClient {
        val objectMapper = ObjectMapper()
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val settings = project.getService(Storer::class.java)!!.settings
        return BitbucketClient(
                createHttpClient(),
                createRequestFactory(settings),
                settings,
                project.getService(CurrentUser::class.java),
                objectMapper.reader(), objectMapper.writer(), listener)
    }

    private fun createRequestFactory(settings: Settings): RequestFactory {
        return if (settings.useAccessTokenAuth) {
            AccessTokenRequestFactory(settings.accessToken)
        } else {
            BasicAuthRequestFactory(settings.login, String(password))
        }
    }

    // "System" client reuses the IDE's own SSLContext/TrustManager.
    fun createHttpClient(): HttpClient = HttpClients.createSystem()
}
