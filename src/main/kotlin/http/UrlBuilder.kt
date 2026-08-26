package http

import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Dependency-free stand-in for com.palominolabs.http:url-builder (unresolvable since JCenter shut down). */
class UrlBuilder private constructor(baseUrl: URL) {
    private val scheme = baseUrl.protocol
    private val authority = baseUrl.authority
    private val basePath = baseUrl.path.trimEnd('/')
    private val pathSegments = mutableListOf<String>()
    private val queryParams = mutableListOf<Pair<String, String>>()

    companion object {
        fun fromUrl(url: URL): UrlBuilder = UrlBuilder(url)

        private fun encode(value: String): String =
                URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }

    fun pathSegments(vararg segments: String): UrlBuilder {
        pathSegments.addAll(segments)
        return this
    }

    fun queryParam(name: String, value: String): UrlBuilder {
        queryParams.add(name to value)
        return this
    }

    fun toUrlString(): String {
        val path = (listOf(basePath) + pathSegments.map { encode(it) }).joinToString("/")
        val query = queryParams.takeIf { it.isNotEmpty() }
                ?.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
        return buildString {
            append(scheme).append("://").append(authority).append(path)
            if (query != null) append('?').append(query)
        }
    }
}
