package bitbucket.cloud.dto

import bitbucket.cloud.toDomain
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Deserialization of the Bitbucket Cloud wire format and its mapping into the domain model.
 * docs: Known incidents */
class ParseTest {
    private val objMapper = ObjectMapper()
    private val prTypeRef = object : TypeReference<PullRequestDto>() {}
    private val prPageTypeRef = object : TypeReference<PageDto<PullRequestDto>>() {}

    @Before
    fun setUp() {
        objMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    @Test
    fun testParsePR() {
        val dto = readPR("pr_sample.json")
        assertNotNull(dto)
        assertEquals(1, dto.participants.size)
    }

    /** A PR nobody has ever added as a reviewer/participant on can come back with the
     * `participants` key missing entirely rather than an empty array - reproduces the real
     * Jackson failure this pins down: "Cannot construct instance of PullRequestDto ... parameter
     * participants". docs: Known incidents */
    @Test
    fun testParsePRWithMissingParticipants() {
        val dto = readPR("pr_sample_missing_participants.json")
        assertNotNull(dto)
        assertTrue(dto.participants.isEmpty())
    }

    /** Same failure, the other shape it was seen in the field: `"participants": null` rather than
     * the key being absent. Both must parse - a plain Kotlin default value alone only covers the
     * missing-key case, not an explicit JSON null. docs: Known incidents */
    @Test
    fun testParsePRWithNullParticipants() {
        val dto = readPR("pr_sample_null_participants.json")
        assertNotNull(dto)
        assertTrue(dto.participants.isEmpty())
    }

    @Test
    fun testParsePageOfPRs() {
        val page = objMapper.reader().forType(prPageTypeRef)
                .readValue<PageDto<PullRequestDto>>(javaClass.getResourceAsStream("pr_page_sample.json"))
        assertNotNull(page)
        assertEquals(1, page.values.size)
    }

    @Test
    fun testMapsToDomain() {
        val dto = readPR("pr_sample.json")
        val pr = dto.toDomain()
        assertEquals(dto.id, pr.id)
        assertEquals(dto.title, pr.title)
        assertEquals(dto.source.branch.name, pr.fromBranch)
        assertEquals(dto.destination.branch.name, pr.toBranch)
        assertEquals(dto.author.uuid, pr.author.userName)
        assertEquals(dto.author.displayName, pr.author.displayName)
        assertEquals(1, pr.reviewers.size)
        assertEquals(dto.commentCount, pr.commentCount)
        assertEquals(dto.links.html.href, pr.webUrl)
    }

    /** The bug this whole file exists to pin: a PR with no participants at all must still map to
     * a domain PR with no reviewers and an unapproved author, not throw during parsing.
     * docs: Known incidents */
    @Test
    fun testMissingParticipantsMapsToNoReviewersAndUnapprovedAuthor() {
        val pr = readPR("pr_sample_missing_participants.json").toDomain()
        assertTrue(pr.reviewers.isEmpty())
        assertEquals(false, pr.author.approved)
    }

    private fun readPR(resource: String): PullRequestDto =
            objMapper.reader().forType(prTypeRef).readValue(javaClass.getResourceAsStream(resource))
}
