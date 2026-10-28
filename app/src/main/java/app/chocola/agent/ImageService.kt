package app.chocola.agent

import app.chocola.api.ImageClient
import app.chocola.data.*
import org.json.JSONObject
import java.io.File

internal class ImageService(private val repository: Repository) {
    suspend fun generate(session: Session, prompt: String, referencePaths: List<String> = emptyList(), overrides: JSONObject = JSONObject(), direct: Boolean = false,
        onProgress: suspend (ImageBatch, List<Attachment>) -> Unit = { _, _ -> }): List<Attachment> {
        val state = repository.state.value
        val current = state.providers.firstOrNull { it.id == session.providerId } ?: error("当前模型已被删除")
        val (provider, defaults) = ImageModels.resolve(state, current, direct)
        val config = defaults.json()
        mapOf("size" to "size", "quality" to "quality", "background" to "background", "output_format" to "format", "n" to "count",
            "output_compression" to "compression", "input_fidelity" to "inputFidelity", "moderation" to "moderation").forEach { (argument, field) ->
            if (overrides.has(argument)) config.put(field, overrides.get(argument))
        }
        val settings = ImageSettings.from(config)
        ImageModels.validate(settings, provider.model)
        val paths = WorkspacePaths(repository.workspace(session.id))
        val references = referencePaths.map { path ->
            val file = paths.resolve(path)
            val mime = when (file.extension.lowercase()) { "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; else -> "image/png" }
            Attachment(file.name, file.path, mime, file.length())
        }
        val router = settings.routingModel.ifBlank {
            current.model.takeIf { !ImageModels.isImage(current) && ImageModels.sameAccount(current, provider) }
                ?: state.providers.firstOrNull { ImageModels.sameAccount(it, provider) && !ImageModels.isImage(it) }?.model.orEmpty()
        }
        val batch = ImageBatch.forSettings(settings)
        onProgress(batch, emptyList())
        val imagePrompt = prompt + if (references.isEmpty()) "" else "\n\n参考图片按上传顺序：\n" + references.mapIndexed { index, image -> "${index + 1}. 文件「${image.name}」" }.joinToString("\n")
        val result = ImageClient(repository.logs, privateModels = repository.codexAccounts).generate(provider, settings, imagePrompt, references, File(paths.root, "generated"),
            File(repository.context.cacheDir, "api-streams"), session.id, router, onImages = { onProgress(batch, it) })
        repository.filesChanged()
        return result.images
    }
    fun result(session: Session, images: List<Attachment>): String {
        val paths = WorkspacePaths(repository.workspace(session.id))
        return JSONObject().put("images", jsonArray(images.map { image -> JSONObject().put("path", paths.guest(File(image.path)))
            .put("mime", image.mime).put("bytes", image.size).put("width", image.width).put("height", image.height).put("output", image.output?.json() ?: JSONObject.NULL) }))
            .put("warnings", jsonArray(images.map { it.imageWarning }.filter(String::isNotBlank)))
            .put("display", "Images are saved and displayed in the conversation. Dimensions above come from the actual file. If the requested size was not met, explain that limitation without claiming 4K or silently regenerating. Do not repeat image links or base64; use these workspace paths as references for later edits.").toString()
    }
    fun attachments(session: Session, result: String): List<Attachment> {
        val paths = WorkspacePaths(repository.workspace(session.id))
        return JSONObject(result).getJSONArray("images").objects().map { item ->
            val file = paths.resolve(item.getString("path")); Attachment(file.name, file.path, item.getString("mime"), file.length(), item.optInt("width"), item.optInt("height"), item.optJSONObject("output")?.let(ImageOutput::from))
        }
    }
}
