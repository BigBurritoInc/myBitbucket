package credentials

import com.intellij.credentialStore.Credentials
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.config.GitExecutableManager
import util.LOG

/** Shells out to `git credential fill` directly — no platform API exists for reading back a
 * credential git itself stores. Only meaningful for http(s); a `null` return means "nothing to
 * reuse", not an error.
 * docs: Basic Auth */
object GitCredentialHelper {

    private const val TIMEOUT_MILLIS = 3_000

    /** Must be called off the EDT — runs and waits on a subprocess. [workingDirectory] should be
     * a root of the git repository the remote belongs to (git reads its config, including
     * `credential.helper`, relative to the working directory). */
    fun tryFill(project: Project, workingDirectory: VirtualFile, scheme: String, host: String): Credentials? {
        return try {
            val gitPath = GitExecutableManager.getInstance().getPathToGit(project)
            val commandLine = GeneralCommandLine(gitPath, "credential", "fill")
                    .withWorkDirectory(workingDirectory.path)
            val handler = CapturingProcessHandler(commandLine)
            // Written from a startNotified callback, not right after construction: the child
            // process isn't guaranteed to exist yet otherwise, and this ensures the stream is
            // closed (via `use`) so git sees EOF after the blank-line-terminated input.
            handler.addProcessListener(object : ProcessListener {
                override fun startNotified(event: ProcessEvent) {
                    handler.processInput.writer(commandLine.charset).use {
                        it.write("protocol=$scheme\nhost=$host\n\n")
                    }
                }
            })
            val output = handler.runProcess(TIMEOUT_MILLIS)
            if (output.isTimeout || output.exitCode != 0) null else parseCredentials(output.stdout)
        } catch (e: Exception) {
            LOG.debug("git credential fill failed for $scheme://$host", e)
            null
        }
    }

    /** Parses git's `key=value`-per-line credential protocol output. Only "username" and
     * "password" are needed here; any other keys git prints (e.g. "url") are ignored. */
    private fun parseCredentials(stdout: String): Credentials? {
        var username: String? = null
        var password: String? = null
        stdout.lineSequence().forEach { line ->
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            when (line.substring(0, eq)) {
                "username" -> username = line.substring(eq + 1)
                "password" -> password = line.substring(eq + 1)
            }
        }
        val user = username
        val pass = password
        if (user.isNullOrBlank() || pass.isNullOrBlank()) return null
        return Credentials(user, pass)
    }
}
