package bitbucket.server.dto

import bitbucket.server.toDomain
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Deserialization of the Bitbucket Server wire format and its mapping into the domain model.
 *
 * @author Sergey Lukashevich
 */
class ParseTest {
    private val objMapper = ObjectMapper()
    private val prTypeRef = object: TypeReference<PullRequestDto>() { }
    private val prPageTypeRef = object: TypeReference<PageDto<PullRequestDto>>() { }

    @Before
    fun setUp() {
        objMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    @Test
    fun testParsePR() {
        assertNotNull(readPR("pr_sample.json"))
    }

    @Test
    fun testParsePRWithoutEmail() {
        // Some reviewers can be without email. Here we just check that such json is parsed correctly
        assertNotNull(readPR("pr_sample_noemail.json"))
    }

    /**
     * Bitbucket omits `nextPageStart` on the last page. PageDto declares it non-null, which is only
     * safe because replayPageRequest checks isLastPage first — pin both here.
     */
    @Test
    fun testParsePageOfPRs() {
        val page = objMapper.reader().forType(prPageTypeRef)
                .readValue<PageDto<PullRequestDto>>(javaClass.getResourceAsStream("pr_page_sample.json"))
        assertNotNull(page)
        assertEquals(1, page.values.size)
        assertTrue(page.isLastPage)
    }

    /** The mapping is the only place Server's field names are known — pin the ones that move. */
    @Test
    fun testMapsToDomain() {
        val dto = readPR("pr_sample.json")
        val pr = dto.toDomain()
        assertEquals(dto.id, pr.id)
        assertEquals(dto.title, pr.title)
        assertEquals(dto.fromRef.name, pr.fromBranch)
        assertEquals(dto.toRef.name, pr.toBranch)
        assertEquals(dto.author.user.name, pr.author.userName)
        assertEquals(dto.author.user.displayName, pr.author.displayName)
        assertEquals(dto.reviewers.size, pr.reviewers.size)
        assertEquals(dto.properties.commentCount, pr.commentCount)
        assertEquals(dto.links.getSelfHref(), pr.webUrl)
        // Server's version counter is what the domain calls the revision.
        assertEquals(dto.version.toLong(), pr.revision)
        // A freshly parsed pull request has no merge status yet.
        assertTrue(!pr.mergeStatus.known)
    }

    /** Server omits `description` entirely when there isn't one; the domain always has a string. */
    @Test
    fun testMissingDescriptionBecomesEmptyString() {
        assertEquals("", readPR("pr_sample_noemail.json").copy(description = null).toDomain().description)
    }

    private fun readPR(resource: String): PullRequestDto =
            objMapper.reader().forType(prTypeRef).readValue(javaClass.getResourceAsStream(resource))
}
