import bitbucket.BitbucketClient
import bitbucket.ClientListener
import bitbucket.CurrentUser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import http.BasicAuthRequestFactory
import org.apache.http.impl.client.HttpClients
import ui.Settings

object Runner {
    @JvmStatic fun main(args: Array<String>) {
        val objectMapper = ObjectMapper()
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val settings = Settings()
        val currentUser = CurrentUser()
        settings.login = args[0]
        settings.url = args[2]
        settings.project = args[3]
        settings.slug = args[4]
        val client = BitbucketClient(
                HttpClients.createDefault(),
                BasicAuthRequestFactory(args[0], args[1]),
                settings,
                currentUser,
                objectMapper.reader(), objectMapper.writer(),
                object: ClientListener {
                    override fun invalidCredentials() {
                        println("invalidCredentials")
                    }

                    override fun actionForbidden() {
                        println("Forbidden")
                    }

                    override fun requestFailed(e: Exception) {
                        println("requestFailed: $e")
                    }
                })

        val prs = client.openPRs()
        println("Current user (from X-AUSERNAME): ${currentUser.name}")
        if (prs == null) {
            println("Pull requests could not be fetched")
        } else {
            prs.own.forEach { println("OwnPR: $it") }
            prs.reviewing.forEach { println("ReviewedPR: $it") }
        }
    }
}