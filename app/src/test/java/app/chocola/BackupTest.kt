package app.chocola

import app.chocola.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

class BackupTest {
    private fun encrypted(data: ByteArray, password: String = "correct-password"): ByteArray = ByteArrayOutputStream().also { out ->
        BackupCipher.encrypt(out, password.toCharArray()).use { it.write(data) }
    }.toByteArray()
    private fun decrypted(data: ByteArray, password: String = "correct-password"): ByteArray = ByteArrayOutputStream().also { out ->
        BackupCipher.decrypt(ByteArrayInputStream(data), out, password.toCharArray())
    }.toByteArray()
    @Test fun fullChunksAndTailRestoreExactlyWithFreshNonces() {
        val source = ByteArray(800_103).also(java.security.SecureRandom()::nextBytes)
        val first = encrypted(source); val second = encrypted(source)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(source, decrypted(first))
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("correct-password"))
    }
    @Test fun wrongPasswordChangedChunkAndMissingEndMarkerAreRejected() {
        val data = encrypted(ByteArray(270_000) { (it % 251).toByte() })
        assertTrue(runCatching { decrypted(data, "wrong-password") }.isFailure)
        val corrupt = data.copyOf().also { it[90] = (it[90].toInt() xor 1).toByte() }
        assertTrue(runCatching { decrypted(corrupt) }.isFailure)
        assertTrue(runCatching { decrypted(data.copyOf(data.size - 20)) }.isFailure)
        assertTrue(runCatching { decrypted(data + byteArrayOf(1)) }.isFailure)
    }
    @Test fun interruptedFileSwapRollsBackAndCommittedSwapSurvivesRecovery() {
        val root = Files.createTempDirectory("chocola-restore").toFile()
        try {
            val old = File(root, "workspaces/old.txt").apply { parentFile.mkdirs(); writeText("original") }
            val tx = RestoreFiles(root)
            File(tx.incoming, "payload/workspaces/new.txt").apply { parentFile.mkdirs(); writeText("replacement") }
            assertTrue(runCatching { tx.apply("first") { error("simulated DB transaction failure") } }.isFailure)
            tx.recover(null)
            assertEquals("original", old.readText())
            assertFalse(File(root, "workspaces/new.txt").exists())
            File(tx.incoming, "payload/workspaces/new.txt").apply { parentFile.mkdirs(); writeText("replacement") }
            tx.apply("second") { }
            tx.recover("second")
            assertFalse(old.exists())
            assertEquals("replacement", File(root, "workspaces/new.txt").readText())
            assertFalse(File(root, ".restore-journal.json").exists())
        } finally { RestoreFiles.deleteTree(root) }
    }
    @Test fun cleanupNeverFollowsSandboxSymlinks() {
        val root = Files.createTempDirectory("chocola-links").toFile()
        val outside = Files.createTempDirectory("chocola-external").toFile()
        try {
            File(outside, "kept.txt").writeText("keep")
            Files.createSymbolicLink(File(root, "external").toPath(), outside.toPath())
            RestoreFiles.deleteTree(root)
            assertEquals("keep", File(outside, "kept.txt").readText())
        } finally { RestoreFiles.deleteTree(root); RestoreFiles.deleteTree(outside) }
    }
    @Test fun encryptedPayloadRoundTripsLegacyReportsLinuxNamesLinksAndExecutableModes() {
        val source = Files.createTempDirectory("choco-backup-source").toFile()
        val destination = Files.createTempDirectory("choco-backup-target").toFile()
        val meta = Files.createTempFile("choco-backup-meta", ".jsonl").toFile()
        try {
            val report = "scheduled:${newId()}:${newId()}"
            val name = "workspaces/$report/report 12:30\\result.txt"
            File(source, name).apply { parentFile.mkdirs(); writeText("原始数据\nabc") }
            val executable = File(source, "skills/sample/run.sh").apply { parentFile.mkdirs(); writeText("#!/bin/sh\nprintf ok") }
            Files.setPosixFilePermissions(executable.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
            Files.createSymbolicLink(File(executable.parentFile, "alias").toPath(), executable.toPath().fileName)
            Files.createSymbolicLink(File(executable.parentFile, "external").toPath(), java.nio.file.Paths.get("/usr/bin/python3"))
            val encrypted = ByteArrayOutputStream()
            BackupCipher.encrypt(encrypted, "roundtrip-password".toCharArray()).use { cipher ->
                ZipOutputStream(cipher).use { zip ->
                    FullBackupFiles.write(source, zip, meta, BackupBudget()) { }
                    zip.putNextEntry(ZipEntry("files.jsonl")); zip.write(meta.readBytes()); zip.closeEntry()
                }
            }
            val verified = ByteArrayOutputStream()
            BackupCipher.decrypt(ByteArrayInputStream(encrypted.toByteArray()), verified, "roundtrip-password".toCharArray())
            ArchivePaths.unpack(ByteArrayInputStream(verified.toByteArray()), destination, linuxNames = true)
            FullBackupFiles.restore(destination) { }
            val restored = File(destination, "payload/$name")
            assertEquals("原始数据\nabc", restored.readText())
            val script = File(destination, "payload/skills/sample/run.sh")
            assertTrue(Files.getPosixFilePermissions(script.toPath()).contains(PosixFilePermission.OWNER_EXECUTE))
            assertEquals("run.sh", Files.readSymbolicLink(File(script.parentFile, "alias").toPath()).toString())
            assertEquals("/usr/bin/python3", Files.readSymbolicLink(File(script.parentFile, "external").toPath()).toString())
            SessionIds.requireValid(report)
        } finally { RestoreFiles.deleteTree(source); RestoreFiles.deleteTree(destination); meta.delete() }
    }
    @Test fun linuxBackupNamesNeverWeakenTraversalOrSymlinkBoundaryChecks() {
        val root = Files.createTempDirectory("choco-backup-paths").toFile()
        val outside = Files.createTempDirectory("choco-backup-outside").toFile()
        try {
            listOf("../escape", "payload/../escape", "/absolute", "payload//a", "payload/./a", "payload/a\u0000b").forEach {
                assertTrue(it, runCatching { ArchivePaths.resolve(root, it, linuxNames = true) }.isFailure)
            }
            assertEquals(File(root, "payload/12:30.txt"), ArchivePaths.resolve(root, "payload/12:30.txt", linuxNames = true))
            assertTrue(runCatching { ArchivePaths.resolve(root, "payload/12:30.txt") }.isFailure)
            Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
            assertTrue(runCatching { ArchivePaths.resolve(root, "escape/private.txt", linuxNames = true) }.isFailure)
        } finally { RestoreFiles.deleteTree(root); RestoreFiles.deleteTree(outside) }
    }
    @Test fun exporterRejectsDataBeyondTheRestorersLimitsBeforeDeliveringABackup() {
        val budget = BackupBudget()
        budget.consume(16_000_000_000L)
        assertTrue(runCatching { budget.consume(1) }.isFailure)
        val record = JSONObject().put("kind", "session").put("id", "x".repeat(301))
        assertTrue(runCatching { BackupBudget().record(record, record.toString()) }.isFailure)
    }
}
