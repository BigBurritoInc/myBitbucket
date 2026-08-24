package bitbucket.httpparams

import http.UrlBuilder

interface HttpRequestParameter {
    fun apply(urlBuilder: UrlBuilder)
}