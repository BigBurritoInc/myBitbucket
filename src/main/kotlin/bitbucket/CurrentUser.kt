package bitbucket

import com.intellij.openapi.components.Service
import org.apache.http.HttpResponse
import util.LOG

/**
 * The Bitbucket username the configured credentials belong to, learned from the `X-AUSERNAME`
 * response header rather than from Settings. See CLAUDE.md "Current user" for why Settings can't
 * supply it.
 *
 * One instance per open project (light service, no plugin.xml registration needed) — two projects
 * can be configured against different servers or different accounts. See CLAUDE.md
 * "Per-project state".
 */
@Service(Service.Level.PROJECT)
class CurrentUser {

    companion object {
        private const val HEADER = "X-AUSERNAME"
    }

    // Written from the poll thread, read from the action executor and the EDT. Every write is a
    // single unconditional assignment of an immutable String, so @Volatile is enough — there is no
    // read-modify-write to make atomic.
    @Volatile
    private var captured: String? = null

    val name: String? get() = captured

    /**
     * Bitbucket echoes the authenticated username on every response. Reading a header does not
     * consume the response entity, so this is safe to call before the body is parsed.
     */
    fun captureFrom(response: HttpResponse) {
        val value = response.getFirstHeader(HEADER)?.value?.trim()
        if (value.isNullOrEmpty() || value == captured) return
        LOG.debug("Current Bitbucket user resolved from $HEADER: $value")
        captured = value
    }

    /** Called when the poll is rescheduled, so a newly pasted token can't inherit the old identity. */
    fun clear() {
        captured = null
    }
}
