package app.chocola.agent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

internal object SandboxFiles {
    /** Copy real files and preserve links themselves. Never share writable inodes. */
    suspend fun copy(template: File, destination: File) {
        val caller = currentCoroutineContext()
        val source = template.toPath()
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                caller.ensureActive()
                Files.createDirectories(destination.toPath().resolve(source.relativize(path)))
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                caller.ensureActive()
                Files.copy(path, destination.toPath().resolve(source.relativize(path)), LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
