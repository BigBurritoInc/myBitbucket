package bitbucket

import bitbucket.httpparams.Limit
import bitbucket.httpparams.PROrder
import bitbucket.httpparams.PRState
import bitbucket.httpparams.Start
import domain.PR
import domain.ReviewStatus
import http.UrlBuilder
import ui.Settings
import java.net.URL

/** Open pull requests of the configured repository, split by role. A pull request the user both
 * authored and reviews appears in BOTH lists - matching what the two separate role-filtered
 * requests used to return. */
data class RepositoryPRs(val own: List<PR>, val reviewing: List<PR>)

/** One page of the configured repository's open pull requests, repository-scoped (unlike the
 * account-wide `/inbox/pull-requests` this replaced).
 * docs: Fetching pull requests */
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
    val own = prs.filter { it.author.userName.sameUserAs(currentUser) }
    val reviewing = prs.filter { pr -> pr.reviewers.any { it.userName.sameUserAs(currentUser) } }
    return RepositoryPRs(own, reviewing)
}

/** How much of the user's attention a PR still wants. Doubles as the Reviewing list's sort order
 * (declaration order = display order) and the "Show already approved" collapse rule. */
enum class ReviewAttention {
    /** Not looked at yet, as far as the user's own review status shows. */
    NEEDS_ATTENTION,
    /** The user asked for changes and is waiting on the author. */
    CHANGES_REQUESTED,
    /** The user already approved; nothing left to do. */
    APPROVED
}

/** Null user (not resolved yet, before the first poll) counts as [ReviewAttention.NEEDS_ATTENTION],
 * so nothing is hidden/reordered on a guess. */
fun PR.attentionFor(user: String?): ReviewAttention {
    if (user == null) return ReviewAttention.NEEDS_ATTENTION
    val me = reviewers.firstOrNull { it.userName.sameUserAs(user) } ?: return ReviewAttention.NEEDS_ATTENTION
    return when {
        me.approved || me.status == ReviewStatus.APPROVED -> ReviewAttention.APPROVED
        me.status == ReviewStatus.NEEDS_WORK -> ReviewAttention.CHANGES_REQUESTED
        else -> ReviewAttention.NEEDS_ATTENTION
    }
}

/** Bitbucket compares usernames case-insensitively; `X-AUSERNAME`'s casing need not match a pull
 * request payload's on LDAP-backed servers. */
fun String.sameUserAs(other: String): Boolean {
    val a = this.trim()
    val b = other.trim()
    return a.isNotEmpty() && a.equals(b, ignoreCase = true)
}
