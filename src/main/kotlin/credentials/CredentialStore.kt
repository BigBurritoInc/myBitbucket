package credentials

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/** Wraps PasswordSafe, keyed by server host[:port]. Every function requires the caller to already
 * be off the EDT (PasswordSafe access is blocking IO), and this wrapper does not hide that thread
 * hop.
 * docs: Basic Auth */
object CredentialStore {

    private const val SUBSYSTEM = "myBitbucket"

    /** [hostAuthority] is host[:port], e.g. "bitbucket.example.com" or
     * "bitbucket.example.com:7990" — the same value [credentials.GitCredentialHelper] looks
     * secrets up by. */
    fun attributesFor(hostAuthority: String): CredentialAttributes =
            CredentialAttributes(generateServiceName(SUBSYSTEM, hostAuthority))

    /** Must be called off the EDT. Returns null if nothing is stored for [hostAuthority]. */
    fun read(hostAuthority: String): Credentials? = PasswordSafe.instance.get(attributesFor(hostAuthority))

    /** Must be called off the EDT. */
    fun write(hostAuthority: String, credentials: Credentials) {
        PasswordSafe.instance.set(attributesFor(hostAuthority), credentials)
    }

    /** Must be called off the EDT. Used once a stored secret turns out to be invalid (e.g. a
     * 401 from the server) so the user is prompted again next time instead of silently retrying
     * a dead credential. */
    fun clear(hostAuthority: String) {
        PasswordSafe.instance.set(attributesFor(hostAuthority), null)
    }

    /** Host[:port] for [baseUrl] — the same shape [discovery.DiscoveredRemote.hostAuthority] and
     * `GitCredentialHelper` use, so a credential saved from a discovered remote is found again
     * from a persisted `Settings.url`. Null if [baseUrl] doesn't parse as a URI. */
    fun hostAuthorityFor(baseUrl: String): String? {
        val uri = try {
            java.net.URI(baseUrl)
        } catch (e: Exception) {
            return null
        }
        val host = uri.host ?: return null
        return if (uri.port > 0) "$host:${uri.port}" else host
    }
}
