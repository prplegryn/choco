package app.chocola.api

import app.chocola.data.*
import org.json.JSONArray
import org.json.JSONObject

internal object ImageRequest {
    fun endpoint(provider: Provider, edit: Boolean, responses: Boolean = false): String {
        var root = provider.baseUrl.trim().trimEnd('/')
        listOf("/images/generations", "/images/edits", "/chat/completions", "/responses", "/messages").firstOrNull { root.endsWith(it) }?.let { root = root.removeSuffix(it) }
        return root + if (responses) "/responses" else if (edit) "/images/edits" else "/images/generations"
    }
    fun parameters(settings: ImageSettings, model: String, edit: Boolean): JSONObject {
        ImageModels.validate(settings, model)
        val family = ImageModels.family(model)
        return JSONObject(settings.extraBody.ifBlank { "{}" }).apply {
            if (settings.size != "auto") put("size", settings.size)
            if (settings.quality != "auto") put("quality", settings.quality)
            if (family !in setOf("dalle2", "dalle3")) {
                put("background", settings.background).put("output_format", settings.format)
                if (settings.format != "png") put("output_compression", settings.compression)
                if (family.startsWith("gpt")) put("moderation", settings.moderation)
                if (edit && ImageModels.supportsFidelity(model) && settings.inputFidelity != "auto") put("input_fidelity", settings.inputFidelity)
            }
        }
    }
    fun body(provider: Provider, settings: ImageSettings, prompt: String, edit: Boolean = false): JSONObject =
        parameters(settings, provider.model, edit).put("model", provider.model).put("prompt", prompt).put("n", settings.count).apply {
            // GPT Image returns base64 by default and rejects response_format on the official API.
            if (!ImageModels.family(provider.model).startsWith("gpt")) put("response_format", "b64_json")
        }
    fun responseBody(provider: Provider, settings: ImageSettings, prompt: String, routingModel: String, edit: Boolean): JSONObject {
        require(routingModel.isNotBlank() && !ImageModels.isImage(routingModel)) { "请选择支持绘图工具的调度模型" }
        val tool = parameters(settings, provider.model, edit).put("type", "image_generation").put("model", provider.model)
        if (edit) tool.put("action", "edit") else tool.put("action", "generate")
        return JSONObject().put("model", routingModel).put("stream", true).put("store", false)
            .put("tools", JSONArray().put(tool)).put("tool_choice", JSONObject().put("type", "image_generation"))
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", prompt)))))
    }
}
