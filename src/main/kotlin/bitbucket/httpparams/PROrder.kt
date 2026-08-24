package bitbucket.httpparams

import http.UrlBuilder

enum class PROrder(private val paramValue: String): HttpRequestParameter {
    NEWEST("newest"),
    OLDEST("oldest"),
    PARTICIPANT_STATUS("participant_status");

    override fun apply(urlBuilder: UrlBuilder) {
        urlBuilder.queryParam("order", paramValue)
    }
}