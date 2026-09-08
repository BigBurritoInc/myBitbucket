package http

import bitbucket.ClientListener
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectReader
import org.apache.http.HttpResponse
import org.apache.http.HttpStatus
import util.LOG
import java.io.ByteArrayInputStream
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
                // Bitbucket Cloud's DELETE endpoints (e.g. removing an approval) return 204 with
                // no body - only reachable by the void handle(response) overload in practice, so
                // an empty stream is safe; a typed caller expecting a real body would still fail
                // to parse it, same as before this branch existed. docs: Known incidents
                HttpStatus.SC_NO_CONTENT -> mapper.invoke(ByteArrayInputStream(ByteArray(0)))
                HttpStatus.SC_FORBIDDEN -> {
                    // Bitbucket's error body usually names the actual reason (e.g. a missing
                    // OAuth scope) that a bare "403" doesn't - logged here, the one place both
                    // Server and Cloud calls funnel through. docs: Known incidents
                    LOG.warn("BitBucket request forbidden (403): ${readErrorBodySafely(response)}")
                    listener.actionForbidden()
                    // Don't hand the error body to the body mapper - it isn't the type the caller
                    // asked for, and Jackson would throw something unrelated to the real problem.
                    throw ForbiddenException
                }
                HttpStatus.SC_UNAUTHORIZED -> {
                    LOG.warn("BitBucket request unauthorized (401) - check the configured " +
                            "credentials/access token: ${readErrorBodySafely(response)}")
                    listener.invalidCredentials()
                    throw UnauthorizedException
                }
                HttpStatus.SC_NOT_FOUND -> {
                    // Distinct from the generic branch below: for the repository-scoped endpoints a
                    // 404 almost always means the configured project/slug is wrong, which deserves a
                    // pointed message instead of a stack trace. See ServerBitbucketClient.openPRs().
                    LOG.warn("BitBucket resource not found (404)")
                    throw NotFoundException
                }
                else -> {
                    LOG.warn("Unexpected BitBucket response: $statusCode ${status.reasonPhrase}")
                    throw RuntimeException("Status code: ${status.statusCode}, reason ${status.reasonPhrase}")
                }
            }
        }

        // Best-effort diagnostic read, deliberately separate from the real body mapper above:
        // must never itself throw (an already-consumed or absent entity) and mask the real
        // status-code error being reported. Bounded so a huge/unexpected body can't bloat the log.
        private fun readErrorBodySafely(response: HttpResponse): String = try {
            response.entity?.content?.bufferedReader(Charsets.UTF_8)?.readText()?.trim()
                    ?.take(500)?.ifBlank { "(empty body)" } ?: "(no body)"
        } catch (e: Exception) {
            "(could not read response body: ${e.message})"
        }
    }

    object UnauthorizedException: RuntimeException()
    object ForbiddenException: RuntimeException()
    object NotFoundException: RuntimeException()
}