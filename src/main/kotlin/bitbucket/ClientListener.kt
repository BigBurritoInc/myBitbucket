package bitbucket

interface ClientListener {
    fun invalidCredentials() {}
    fun actionForbidden() {}
    fun requestFailed(e: Exception) {}

    /**
     * The configured project/repository doesn't exist on the server (or the token can't see it).
     * Separate from [requestFailed] because that one logs at ERROR — which raises the IDE's
     * "internal error" balloon — and counts towards the error budget that stops polling.
     */
    fun repositoryNotFound(message: String) {}

    /** Bitbucket never told us which user the credentials belong to, so PRs can't be attributed. */
    fun currentUserUnknown() {}
}
