package bitbucket

import bitbucket.cloud.BitbucketCloudClient
import bitbucket.server.BitbucketServerClient
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.credentialStore.Credentials
import com.intellij.openapi.project.Project
import credentials.CloudOAuth
import credentials.CredentialStore
import http.AccessTokenRequestFactory
import http.BasicAuthRequestFactory
import http.RequestFactory
import org.apache.http.client.HttpClient
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.HttpClients
import ui.Settings
import ui.Storer

object BitbucketClientFactory {

    // settings.cloud is the one branch point between the two flavours.
    // docs: Adding Bitbucket Cloud support
    fun createClient(project: Project, listener: ClientListener = object : ClientListener {}): BitbucketClient {
        val objectMapper = ObjectMapper()
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val settings = project.getService(Storer::class.java)!!.settings
        val httpClient = createHttpClient()
        val requestFactory = createRequestFactory(settings)
        return if (settings.cloud) {
            BitbucketCloudClient(httpClient, requestFactory, settings, objectMapper.reader(), listener)
        } else {
            BitbucketServerClient(httpClient, requestFactory, settings,
                    project.getService(CurrentUser::class.java),
                    objectMapper.reader(), objectMapper.writer(), listener)
        }
    }

    // Reads the secret fresh from PasswordSafe/CloudOAuth every time a client is built (must run
    // off the EDT). Cloud always resolves to an OAuth access token now.
    // docs: Adding Bitbucket Cloud support
    private fun createRequestFactory(settings: Settings): RequestFactory {
        if (settings.cloud) {
            return AccessTokenRequestFactory(CloudOAuth.freshAccessToken() ?: "")
        }
        migrateLegacySecretIfNeeded(settings)
        val hostAuthority = CredentialStore.hostAuthorityFor(settings.url)
        val secret = hostAuthority?.let { CredentialStore.read(it) }?.getPasswordAsString() ?: ""
        return if (settings.useAccessTokenAuth) {
            AccessTokenRequestFactory(secret)
        } else {
            BasicAuthRequestFactory(settings.login, secret)
        }
    }

    // One-time migration of a pre-0.6 install's plaintext Settings.accessToken into
    // CredentialStore. Must run off the EDT (like CredentialStore itself).
    // docs: Basic Auth
    private fun migrateLegacySecretIfNeeded(settings: Settings) {
        if (settings.accessToken.isBlank()) return
        val hostAuthority = CredentialStore.hostAuthorityFor(settings.url) ?: return
        CredentialStore.write(hostAuthority, Credentials(settings.login, settings.accessToken))
        settings.accessToken = ""
    }

    // useSystemProperties() matches HttpClients.createSystem(), plus setAuthenticationEnabled(false)
    // to avoid a platform NPE on a real 401 (a wrong password once crashed the poll here).
    // docs: Known incidents
    fun createHttpClient(): HttpClient = HttpClients.custom()
            .useSystemProperties()
            .setDefaultRequestConfig(RequestConfig.custom().setAuthenticationEnabled(false).build())
            .build()
}
