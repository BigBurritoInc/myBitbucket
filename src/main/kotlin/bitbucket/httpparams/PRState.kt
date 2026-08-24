package bitbucket.httpparams

import http.UrlBuilder

enum class PRState(private val paramValue: String): HttpRequestParameter {
    ALL("all"),
    OPEN("open"),
    DECLINED("declined"),
    MERGED("merged");

    override fun apply(urlBuilder: UrlBuilder) {
        urlBuilder.queryParam("state", paramValue)
    }
}