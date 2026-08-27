package bitbucket

import bitbucket.data.PR
import bitbucket.httpparams.Limit
import bitbucket.httpparams.PROrder
import bitbucket.httpparams.PRState
import bitbucket.httpparams.Start
import http.UrlBuilder
import ui.Settings
import java.net.URL

/**
 * Open pull requests of the configured repository, split by the current user's role.
 *
 * A pull request the user both authored and reviews appears in BOTH lists — that is what the two
 * separate role-filtered requests used to return, and both tabs should still show it.
 */
data class RepositoryPRs(val own: List<PR>, val reviewing: List<PR>)

/**
 * One page of the configured repository's open pull requests.
 *
 * Repository-scoped on purpose: the account-wide `/inbox/pull-requests` this replaced returned every
 * pull request on the whole server and was filtered down client-side, and it has no Bitbucket Cloud
 * equivalent. See CLAUDE.md "Fetching pull requests".
 */
fun pullRequestsUrl(settings: Settings, start: Int, limit: Int, avatarSize: Int): String {
    val urlBuilder = UrlBuilder.fromUrl(URL(settings.url))
            .pathSegments("rest", "api", "1.0",
                    "projects", settings.project, "repos", settings.slug, "pull-requests")
    PRState.OPEN.apply(urlBuilder)
    // An explicit, stable order is what makes start-based paging safe when someone opens a pull
    // request midway through the paging.
    PROrder.NEWEST.apply(urlBuilder)
    Start(start).apply(urlBuilder)
    Limit(limit).apply(urlBuilder)
    urlBuilder.queryParam("avatarSize", avatarSize.toString())
    return urlBuilder.toUrlString()
}

fun partitionPRs(prs: List<PR>, currentUser: String): RepositoryPRs {
    // Deliberately two passes rather than List.partition: a self-reviewed PR belongs in both lists.
    val own = prs.filter { it.author.user.name.sameUserAs(currentUser) }
    val reviewing = prs.filter { pr -> pr.reviewers.any { it.user.name.sameUserAs(currentUser) } }
    return RepositoryPRs(own, reviewing)
}

/**
 * Whether [user] has already approved this pull request. Null (the current user isn't known yet)
 * counts as "not approved", so nothing gets hidden before the first poll resolves the username.
 */
fun PR.isApprovedBy(user: String?): Boolean =
        user != null && reviewers.any { it.approved && it.user.name.sameUserAs(user) }

/**
 * Bitbucket treats usernames case-insensitively, and `X-AUSERNAME` echoes the stored casing, which
 * on LDAP-backed servers need not match the casing inside pull request payloads.
 */
fun String.sameUserAs(other: String): Boolean {
    val a = this.trim()
    val b = other.trim()
    return a.isNotEmpty() && a.equals(b, ignoreCase = true)
}
