package bitbucket

interface ClientListener {
    fun invalidCredentials() {}
    fun actionForbidden() {}
    fun requestFailed(e: Exception) {}

    /** Configured project/repository doesn't exist on the server, or the token can't see it —
     * separate from [requestFailed] since that logs at ERROR and counts toward the poll-stopping
     * error budget. */
    fun repositoryNotFound(message: String) {}

    /** Bitbucket never told us which user the credentials belong to, so PRs can't be attributed. */
    fun currentUserUnknown() {}
}
