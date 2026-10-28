package app.chocola.agent

import app.chocola.data.*
import org.json.JSONObject

internal object NotebookTools {
    val names = setOf("list_notebooks", "read_notebook", "create_notebook", "update_notebook", "organize_notebooks")
    val writes = names - setOf("list_notebooks", "read_notebook")
    fun execute(store: NotebookStore, call: ToolCall): String {
        val a = JSONObject(call.arguments)
        return when (call.name) {
            "list_notebooks" -> jsonArray(store.list(a.optString("path"), a.optString("query")).map { it.json() }).toString()
            "read_notebook" -> store.read(a.getString("path")).let { JSONObject().put("path", it.path).put("content", it.text).put("revision", it.revision).toString() }
            "create_notebook" -> store.create(a.optString("parent"), a.getString("name"), a.optBoolean("folder"), a.optString("content")).json().toString()
            "update_notebook" -> store.save(a.getString("path"), a.getString("content"), a.getString("revision")).let { JSONObject().put("path", it.path).put("revision", it.revision).put("saved", true).toString() }
            "organize_notebooks" -> if (a.optBoolean("delete")) {
                store.trash(a.getString("path")).let { JSONObject().put("moved_to_trash", it.path).put("recoverable", true).toString() }
            } else {
                val path = a.getString("path")
                store.move(path, a.optString("parent", path.substringBeforeLast('/', "")), a.optString("name", path.substringAfterLast('/'))).json().toString()
            }
            else -> error("未知笔记工具")
        }
    }
}
