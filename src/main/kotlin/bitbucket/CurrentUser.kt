package bitbucket

import com.intellij.openapi.components.Service
import org.apache.http.HttpResponse
import util.LOG
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** The Bitbucket username the credentials belong to (learned from `X-AUSERNAME` rather than
 * Settings) - one instance per project (two projects can be configured against different servers
 * or accounts).
 * docs: Current user, Per-project state */
@Service(Service.Level.PROJECT)
class CurrentUser {

    companion object {
        private const val HEADER = "X-AUSERNAME"
    }

    // Written from the poll thread, read from the action executor and the EDT. Every write is a
    // single unconditional assignment of an immutable String, so @Volatile is enough - there is no
    // read-modify-write to make atomic.
    @Volatile
    private var captured: String? = null

    val name: String? get() = captured

    // Paired with the name it was resolved for, so a changed identity will not reuse a stale slug.
    @Volatile
    private var slugEntry: Pair<String, String>? = null

    fun slugFor(name: String): String? = slugEntry?.takeIf { it.first == name }?.second

    fun rememberSlug(name: String, slug: String) {
        slugEntry = name to slug
    }

    /** Bitbucket echoes the authenticated username on every response; reading a header doesn't
     * consume the entity, so this is safe to call before the body is parsed. */
    fun captureFrom(response: HttpResponse) {
        val value = response.getFirstHeader(HEADER)?.value?.trim()?.let(::decodeUserName)
        if (value.isNullOrEmpty() || value == captured) return
        LOG.debug("Current Bitbucket user resolved from $HEADER: $value")
        captured = value
    }

    /** Called when the poll is rescheduled, so a newly pasted token can't inherit the old identity. */
    fun clear() {
        captured = null
        slugEntry = null
    }
}

/** `X-AUSERNAME` is percent-encoded (`john%40corp.com`), while pull request payloads aren't.
 * `+` is kept literal - only `%XX` escapes are decoded.
 * docs: Current user */
internal fun decodeUserName(raw: String): String = try {
    URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8)
} catch (e: IllegalArgumentException) {
    raw
}
