package bitbucket.data

import com.fasterxml.jackson.annotation.JsonProperty

data class Links(@JsonProperty("self")private val selfLink: List<Link>) {
    data class Link(@JsonProperty("href") val href: String)

    fun getSelfHref(): String = selfLink.first().href

    // Fallback only — this is the web-UI path, which ignores access-token auth and answers with
    // Bitbucket's generic default avatar. Prefer User.avatarHref(); see CLAUDE.md.
    fun getIconHref(): String = "${selfLink.first().href}/avatar.png?s=24"

}