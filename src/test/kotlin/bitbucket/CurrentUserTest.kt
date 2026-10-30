package bitbucket

import org.apache.http.HttpVersion
import org.apache.http.message.BasicHttpResponse
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurrentUserTest {

    @Test
    fun emailStyleHeaderIsDecoded() {
        assertEquals("john@corp.com", decodeUserName("john%40corp.com"))
    }

    @Test
    fun plusAndPlainNamesStayAsIs() {
        assertEquals("john+dev", decodeUserName("john+dev"))
        assertEquals("jdoe", decodeUserName("jdoe"))
    }

    @Test
    fun malformedEscapeFallsBackToRawValue() {
        assertEquals("bad%zz", decodeUserName("bad%zz"))
    }

    @Test
    fun capturedHeaderMatchesPayloadUserName() {
        val currentUser = CurrentUser()
        val response = BasicHttpResponse(HttpVersion.HTTP_1_1, 200, "OK")
        response.addHeader("X-AUSERNAME", "john%40corp.com")
        currentUser.captureFrom(response)
        assertEquals("john@corp.com", currentUser.name)
        assertTrue("John@Corp.com".sameUserAs(currentUser.name!!))
    }

    @Test
    fun slugIsTiedToItsNameAndCleared() {
        val currentUser = CurrentUser()
        currentUser.rememberSlug("john@corp.com", "john_corp.com")
        assertEquals("john_corp.com", currentUser.slugFor("john@corp.com"))
        assertNull(currentUser.slugFor("someone.else"))
        currentUser.clear()
        assertNull(currentUser.slugFor("john@corp.com"))
    }
}
