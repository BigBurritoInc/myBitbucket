package bitbucket.httpparams

import http.UrlBuilder

enum class PRState(private val paramValue: String): HttpRequestParameter {
    // Uppercase: Bitbucket Server normalises case, Bitbucket Cloud does not.
    ALL("ALL"),
    OPEN("OPEN"),
    DECLINED("DECLINED"),
    MERGED("MERGED");

    override fun apply(urlBuilder: UrlBuilder) {
        urlBuilder.queryParam("state", paramValue)
    }
}