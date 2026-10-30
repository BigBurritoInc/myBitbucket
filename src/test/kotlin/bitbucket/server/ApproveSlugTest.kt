package bitbucket.server

import bitbucket.ClientListener
import bitbucket.CurrentUser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import domain.PR
import domain.Participant
import domain.ReviewStatus
import http.RequestFactory
import org.apache.http.HttpMessage
import org.apache.http.HttpResponse
import org.apache.http.HttpVersion
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpUriRequest
import org.apache.http.entity.StringEntity
import org.apache.http.message.BasicHttpResponse
import org.junit.Test
import ui.Settings
import java.lang.reflect.Proxy
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Approve must address the participant by slug, not by the (email-style) name - issue #77. */
class ApproveSlugTest {

    private val settings = Settings(project = "PROJ", slug = "repo", url = "https://bb.example.com")
    private val mapper = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private val requests = mutableListOf<HttpUriRequest>()

    private fun client(users: (HttpUriRequest) -> HttpResponse, currentUser: CurrentUser = CurrentUser()) =
            BitbucketServerClient(fakeHttpClient(users), NoAuth, settings, currentUser,
                    mapper.reader(), mapper.writer(), object : ClientListener {})

    private fun fakeHttpClient(users: (HttpUriRequest) -> HttpResponse): HttpClient =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpClient::class.java)) { _, method, args ->
                require(method.name == "execute" && args?.size == 1) { "Unexpected call: $method" }
                val request = args!![0] as HttpUriRequest
                requests.add(request)
                val path = request.uri.path
                when {
                    path.endsWith("/application-properties") -> ok("{}")
                    path.endsWith("/users") -> users(request)
                    else -> ok("")
                }.apply { addHeader("X-AUSERNAME", "john%40corp.com") }
            } as HttpClient

    @Test
    fun approveUsesResolvedSlugAndCachesIt() {
        val currentUser = CurrentUser()
        val usersJson = """{"start":0,"size":2,"limit":25,"isLastPage":true,"values":[
            {"name":"john@corp.com.au","slug":"john_corp.com.au"},
            {"name":"john@corp.com","slug":"john_corp.com"}]}"""
        client({ ok(usersJson) }, currentUser).approve(somePR())
        // A fresh client per action, as Model does, the slug cache must outlive it.
        client({ ok(usersJson) }, currentUser).unapprove(somePR())

        val puts = requests.filter { it.method == "PUT" }
        assertEquals(2, puts.size)
        puts.forEach { assertTrue(it.uri.path.endsWith("/pull-requests/7/participants/john_corp.com"), it.uri.toString()) }
        assertEquals(1, requests.count { it.uri.path.endsWith("/users") }, "slug must be cached per project")
    }

    @Test
    fun failedLookupFallsBackToName() {
        client({ BasicHttpResponse(HttpVersion.HTTP_1_1, 500, "Error") }).approve(somePR())

        val put = requests.single { it.method == "PUT" }
        assertTrue(put.uri.rawPath.endsWith("/participants/john%40corp.com"), put.uri.toString())
    }

    private fun somePR(): PR {
        val now = ZonedDateTime.now()
        val author = Participant("alice", "Alice", false, ReviewStatus.UNAPPROVED)
        return PR(7, "PR#7", "", "from", "to", author, emptySet(), now, now, 0, "", 1, false)
    }

    private fun ok(body: String) = BasicHttpResponse(HttpVersion.HTTP_1_1, 200, "OK").apply { entity = StringEntity(body) }

    private object NoAuth : RequestFactory() {
        override fun addAuthHeader(request: HttpMessage) {}
    }
}
