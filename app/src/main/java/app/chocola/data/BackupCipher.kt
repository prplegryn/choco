package app.chocola.data

import java.io.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Bounded-memory authenticated chunks, including a mandatory authenticated end marker. */
internal object BackupCipher {
    private val magic = "CHCLBKP1".toByteArray()
    private const val iterations = 600_000
    private const val blockSize = 262_144
    private const val maxSize = 16_000_000_000L
    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        require(password.size >= 8) { "备份密码至少 8 个字符" }
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try { val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try { SecretKeySpec(bytes, "AES") } finally { bytes.fill(0) }
        } finally { spec.clearPassword() }
    }
    private fun crypt(mode: Int, key: SecretKeySpec, header: ByteArray, nonce: ByteArray, counter: Int, length: Int, bytes: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, key, GCMParameterSpec(128, ByteBuffer.allocate(12).put(nonce).putInt(counter).array()))
            updateAAD(header); updateAAD(ByteBuffer.allocate(8).putInt(counter).putInt(length).array())
            doFinal(bytes)
        }
    fun encrypt(output: OutputStream, password: CharArray, checkpoint: () -> Unit = {}): OutputStream {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }; val nonce = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val header = ByteBuffer.allocate(44).put(magic).putInt(1).put(salt).put(nonce).putInt(iterations).putInt(blockSize).array()
        val secret = key(password, salt); val stream = DataOutputStream(output)
        stream.write(header)
        return object : OutputStream() {
            private val buffer = ByteArrayOutputStream(blockSize)
            private var count = 0; private var total = 0L; private var closed = false
            private fun frame(final: Boolean = false) {
                checkpoint(); check(!closed)
                val data = buffer.toByteArray(); buffer.reset()
                if (data.isEmpty() && !final) return
                total += data.size; require(total <= maxSize && count < Int.MAX_VALUE) { "备份文件过大" }
                stream.writeInt(data.size); stream.write(crypt(Cipher.ENCRYPT_MODE, secret, header, nonce, count++, data.size, data)); data.fill(0)
            }
            override fun write(b: Int) { buffer.write(b); if (buffer.size() == blockSize) frame() }
            override fun write(b: ByteArray, off: Int, len: Int) {
                var offset = off; var remaining = len
                while (remaining > 0) { val take = minOf(remaining, blockSize - buffer.size()); buffer.write(b, offset, take); offset += take; remaining -= take; if (buffer.size() == blockSize) frame() }
            }
            override fun flush() { stream.flush() }
            override fun close() { if (!closed) try { frame(); frame(final = true); stream.flush() } finally { closed = true; stream.close() } }
        }
    }
    fun decrypt(input: InputStream, output: OutputStream, password: CharArray, checkpoint: () -> Unit = {}) {
        val stream = DataInputStream(input)
        val header = ByteArray(44); stream.readFully(header)
        val view = ByteBuffer.wrap(header); val signature = ByteArray(8).also { view.get(it) }
        require(signature.contentEquals(magic) && view.int == 1) { "不是支持的 Choco 备份" }
        val salt = ByteArray(16).also { view.get(it) }; val nonce = ByteArray(8).also { view.get(it) }
        require(view.int == iterations && view.int == blockSize) { "备份格式不兼容" }
        val secret = key(password, salt)
        var count = 0; var total = 0L
        while (true) {
            checkpoint()
            val length = stream.readInt(); require(length in 0..blockSize && count < Int.MAX_VALUE) { "备份内容不完整" }
            total += length; require(total <= maxSize) { "备份文件过大" }
            val encrypted = ByteArray(length + 16); stream.readFully(encrypted)
            val plain = crypt(Cipher.DECRYPT_MODE, secret, header, nonce, count++, length, encrypted)
            if (length == 0) { require(stream.read() == -1) { "备份末尾存在异常数据" }; break }
            output.write(plain); plain.fill(0)
        }
    }
}
