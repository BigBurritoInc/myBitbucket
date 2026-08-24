package bitbucket.httpparams

import http.UrlBuilder

open class Limit(private val size: Int): HttpRequestParameter {
    override fun apply(urlBuilder: UrlBuilder) {
        urlBuilder.queryParam("limit", size.toString())
    }

    object Default: Limit(25)
}