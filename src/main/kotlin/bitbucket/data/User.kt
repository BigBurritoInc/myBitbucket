package bitbucket.data

import com.fasterxml.jackson.annotation.JsonProperty

data class User(@JsonProperty("name") val name: String,
                // under some circumstances email can be null
                @JsonProperty("emailAddress") val emailAddress: String?,
                @JsonProperty("id") val id: Long,
                @JsonProperty("displayName") val displayName: String,
                @JsonProperty("links") val links: Links,
                // Only populated when the request asked for it — see BitbucketClient.AVATAR_SIZE.
                @JsonProperty("avatarUrl") val avatarUrl: String? = null) {

    /** The avatar URL to fetch. See CLAUDE.md "Known incidents" for why [avatarUrl] must win. */
    fun avatarHref(): String {
        val url = avatarUrl ?: return links.getIconHref()
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        val base = serverBaseUrl() ?: return links.getIconHref()
        return base + if (url.startsWith("/")) url else "/$url"
    }

    private fun serverBaseUrl(): String? {
        val self = links.getSelfHref()
        val schemeEnd = self.indexOf("://")
        if (schemeEnd < 0) return null
        val pathStart = self.indexOf('/', schemeEnd + 3)
        return if (pathStart < 0) self else self.substring(0, pathStart)
    }
}