package http

import bitbucket.ClientListener
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectReader
import org.apache.http.HttpResponse
import org.apache.http.HttpStatus
import util.LOG
import java.io.InputStream

class HttpResponseHandler<T>(
        private val objectReader: ObjectReader,
        private val bodyType: TypeReference<T>,
        private val listener: ClientListener) {

    fun handle(response: HttpResponse): T =
            process(response, { objectReader.forType(bodyType).readValue(it) }, listener)

    companion object {
        /**
         * Use this handle when response body is empty or is not needed
         */
        fun handle(response: HttpResponse) {
            process(response, {})
        }

        private fun  <T> process(
                response: HttpResponse,
                mapper: (InputStream) -> T,
                listener: ClientListener = object: ClientListener {}
        ): T {
            val status = response.statusLine
            val statusCode =  status.statusCode
            LOG.debug("HTTP response: $statusCode ${status.reasonPhrase}")
            return when (statusCode) {
                HttpStatus.SC_OK -> mapper.invoke(response.entity.content)
                HttpStatus.SC_FORBIDDEN -> {
                    LOG.warn("BitBucket request forbidden (403)")
                    listener.actionForbidden()
                    mapper.invoke(response.entity.content)
                }
                HttpStatus.SC_UNAUTHORIZED -> {
                    LOG.warn("BitBucket request unauthorized (401) — check the configured credentials/access token")
                    listener.invalidCredentials()
                    throw UnauthorizedException
                }
                else -> {
                    LOG.warn("Unexpected BitBucket response: $statusCode ${status.reasonPhrase}")
                    throw RuntimeException("Status code: ${status.statusCode}, reason ${status.reasonPhrase}")
                }
            }
        }

    }

    object UnauthorizedException: RuntimeException()
}