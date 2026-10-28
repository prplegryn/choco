package app.chocola.data

import org.json.JSONObject
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

data class Skill(val name: String, val description: String, val enabled: Boolean = true) {
    fun json() = JSONObject().put("name", name).put("description", description).put("enabled", enabled)
    companion object { fun from(j: JSONObject) = Skill(j.getString("name"), j.getString("description"), j.optBoolean("enabled", true)) }
}

internal object SkillFormat {
    private val namePattern = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    fun validateName(name: String) { require(name.length in 1..64 && name.matches(namePattern)) { "Skill 名称使用小写英文、数字和单个连字符，最多 64 字符" } }
    /** Use YAML safe construction, bounded input and no collection aliases. */
    fun parse(document: String): Skill {
        require(document.toByteArray().size <= 512_000) { "SKILL.md 过大" }
        val lines = document.removePrefix("\uFEFF").replace("\r\n", "\n").lines()
        require(lines.firstOrNull() == "---") { "SKILL.md 需要 YAML 开头信息" }
        val end = (1 until lines.size).firstOrNull { lines[it] == "---" } ?: error("SKILL.md 开头信息未闭合")
        val options = LoaderOptions().apply { isAllowDuplicateKeys = false; maxAliasesForCollections = 0; nestingDepthLimit = 12; codePointLimit = 512_000 }
        val fields = Yaml(SafeConstructor(options)).load<Any>(lines.subList(1, end).joinToString("\n")) as? Map<*, *> ?: error("Skill 元数据需要键值格式")
        val name = fields["name"] as? String ?: ""; val description = fields["description"] as? String ?: ""
        validateName(name)
        require(description.length in 1..1024) { "Skill 说明不能为空，最多 1024 字符" }
        require(lines.drop(end + 1).joinToString("\n").isNotBlank()) { "Skill 正文不能为空" }
        return Skill(name, description)
    }
    fun update(original: String, description: String, instructions: String): String {
        val lines = original.removePrefix("\uFEFF").replace("\r\n", "\n").lines()
        val end = (1 until lines.size).first { lines[it] == "---" }
        val yaml = Yaml(SafeConstructor(LoaderOptions().apply { isAllowDuplicateKeys = false; maxAliasesForCollections = 0; codePointLimit = 512_000 }))
        val fields = (yaml.load<Any>(lines.subList(1, end).joinToString("\n")) as Map<*, *>).entries.associate { it.key.toString() to it.value }.toMutableMap()
        fields["description"] = description.trim()
        return "---\n${yaml.dump(fields)}---\n\n${instructions.trim()}\n".also { parse(it) }
    }
    fun create(name: String, description: String, instructions: String): String {
        validateName(name)
        val result = "---\nname: $name\ndescription: ${JSONObject.quote(description.trim())}\n---\n\n${instructions.trim()}\n"
        parse(result); return result
    }
}

/** Shared archive path rules. No platform-specific backslash or drive-name interpretation. */
internal object ArchivePaths {
    fun validate(path: String, linuxNames: Boolean = false) {
        require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\u0000') && (linuxNames || (!path.contains('\\') && !path.contains(':')))) { "归档包含无效路径" }
        require(path.split('/').none { it == ".." || it == "." || it.isEmpty() }) { "归档路径超出目录" }
    }
    fun resolve(root: File, path: String, linuxNames: Boolean = false): File {
        validate(path, linuxNames)
        val file = File(root, path).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator)) { "归档路径超出目录" }
        return file
    }
    fun unpack(input: InputStream, destination: File, limit: Long = 100_000_000, countLimit: Int = 3000, linuxNames: Boolean = false) {
        var total = 0L; var count = 0
        val names = mutableSetOf<String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= countLimit && names.add(entry.name.trimEnd('/'))) { "归档文件过多或路径重复" }
                val target = resolve(destination, entry.name.trimEnd('/'), linuxNames)
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out ->
                        val buffer = ByteArray(32768)
                        while (true) { val n = zip.read(buffer); if (n < 0) break; total += n; require(total <= limit) { "解压后文件过大" }; out.write(buffer, 0, n) }
                    }
                }
                zip.closeEntry()
            }
        }
    }
}
