package bitbucket.httpparams

import http.UrlBuilder

enum class PROrder(private val paramValue: String): HttpRequestParameter {
    // Uppercase: same reasoning as PRState - Bitbucket Server normalises case, Bitbucket Cloud
    // does not, and this endpoint is Server-only.
    NEWEST("NEWEST"),
    OLDEST("OLDEST"),
    PARTICIPANT_STATUS("PARTICIPANT_STATUS");

    override fun apply(urlBuilder: UrlBuilder) {
        urlBuilder.queryParam("order", paramValue)
    }
}