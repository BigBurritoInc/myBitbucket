package bitbucket.httpparams

import http.UrlBuilder

open class Start(private val index: Int): HttpRequestParameter {
    override fun apply(urlBuilder: UrlBuilder) {
        urlBuilder.queryParam("start", index.toString())
    }

    object Zero: Start(0)
}