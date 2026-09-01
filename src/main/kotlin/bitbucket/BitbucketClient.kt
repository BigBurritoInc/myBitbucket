package bitbucket

import domain.Comment
import domain.MergeStatus
import domain.PR

/** What the rest of the plugin needs from a configured Bitbucket repository — Server/Cloud-neutral.
 * docs: Domain model vs wire format, Adding Bitbucket Cloud support */
interface BitbucketClient {

    /** Every open PR, split into ones the user authored vs reviews. `null` means "couldn't fetch" —
     * kept distinct from "none open".
     * docs: Fetching pull requests */
    fun openPRs(): RepositoryPRs?

    // /rest/api/1.0/projects/{projectKey}/repos/{repositorySlug}/pull-requests/{pullRequestId}/participants/{userSlug}
    fun approve(pr: PR)

    fun merge(pr: PR): PR

    fun retrieveMergeStatus(pr: PR): MergeStatus

    /** [pr]'s comments, newest activity included. [sinceCommentId] lets a caller stop paging once
     * it reaches a comment it has already seen — null means "fetch everything".
     * docs: Inbox */
    fun fetchComments(pr: PR, sinceCommentId: Long?): List<Comment>

    /** The identifier [fetchComments]'s and [openPRs]'s authors are compared against to tell
     * "someone else" from "me" — Server's username, Cloud's uuid (the same identifier
     * partitionPRs already uses to split own/reviewing). Null if it couldn't be resolved. Costs
     * no extra request: both implementations already resolve this during [openPRs].
     * docs: Inbox */
    fun currentUserIdentifier(): String?
}
