package app.chocola

import app.chocola.agent.SandboxFiles
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class SandboxFilesTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun packageAndConfigurationWritesStayInTheirConversation() = runBlocking {
        val template = temp.newFolder("template")
        File(template, "etc").mkdir()
        File(template, "etc/config").writeText("default")
        File(template, "bin").mkdir()
        File(template, "bin/tool").apply { writeText("tool"); setExecutable(true) }
        val a = temp.newFolder("a")
        val b = temp.newFolder("b")
        SandboxFiles.copy(template, a); SandboxFiles.copy(template, b)
        File(a, "etc/config").writeText("changed")
        File(a, "bin/tool").appendText("modified")
        assertEquals("default", File(b, "etc/config").readText())
        assertEquals("default", File(template, "etc/config").readText())
        assertEquals("tool", File(b, "bin/tool").readText())
        assertTrue(File(a, "bin/tool").canExecute())
    }
    @Test fun guestSymlinksRemainSymlinksAndAreNotFollowedOnTheHost() = runBlocking {
        val template = temp.newFolder("template")
        Files.createSymbolicLink(File(template, "absolute").toPath(), Paths.get("/guest-only/path"))
        Files.createSymbolicLink(File(template, "relative").toPath(), Paths.get("absolute"))
        val destination = temp.newFolder("conversation")
        SandboxFiles.copy(template, destination)
        assertTrue(Files.isSymbolicLink(File(destination, "absolute").toPath()))
        assertEquals(Paths.get("/guest-only/path"), Files.readSymbolicLink(File(destination, "absolute").toPath()))
        assertEquals(Paths.get("absolute"), Files.readSymbolicLink(File(destination, "relative").toPath()))
    }
}
