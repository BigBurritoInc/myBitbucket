package http

import org.apache.http.HttpMessage
import org.apache.http.client.methods.HttpDelete
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.methods.HttpPut

abstract class RequestFactory {

    fun createPost(url: String): HttpPost {
        val request = HttpPost(url)
        addAuthHeader(request)
        addContentTypeHeader(request)
        return request
    }

    fun createPut(url: String): HttpPut {
        val request = HttpPut(url)
        addAuthHeader(request)
        addContentTypeHeader(request)
        return request
    }
    fun createGet(url: String): HttpGet {
        val request = HttpGet(url)
        addAuthHeader(request)
        return request
    }

    fun createDelete(url: String): HttpDelete {
        val request = HttpDelete(url)
        addAuthHeader(request)
        return request
    }

    abstract fun addAuthHeader(request: HttpMessage);

    private fun addContentTypeHeader(request: HttpMessage) {
        request.setHeader("Content-Type", "application/json")
    }
}