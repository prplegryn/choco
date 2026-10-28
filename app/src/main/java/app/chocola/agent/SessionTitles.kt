package app.chocola.agent

object SessionTitles {
    fun clean(value: String): String = value.trim().trim('"', '\'', '“', '”', '`', '#').substringAfter("标题：").replace(Regex("[\\r\\n]+"), " ").take(36).trim()
    fun fallback(text: String): String = when {
        Regex("服务器|部署|nginx|docker|ssh", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "服务器部署与维护"
        Regex("安卓|android|app|compose|软件", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "应用开发与优化"
        Regex("代码|编程|python|脚本|github", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "编程与问题排查"
        Regex("总结|整理|文档|资料|阅读").containsMatchIn(text) -> "资料整理与分析"
        Regex("旅行|旅游|行程|酒店").containsMatchIn(text) -> "旅行计划"
        else -> "新的交流"
    }
    fun attempt(mode: String, currentTurns: Int, titledTurns: Int, locked: Boolean = false): Int? = when {
        mode != "auto" || locked -> null
        titledTurns == 0 && currentTurns >= 2 -> 2
        titledTurns in 1..4 && currentTurns >= 5 -> 5
        else -> null
    }
    fun usable(title: String) = title.length in 2..36 && title !in setOf("待定", "新会话", "新的交流", "日常聊天", "问候", "暂无标题", "无法确定")
    fun mayUpdate(mode: String, currentTurns: Int, titledTurns: Int) = attempt(mode, currentTurns, titledTurns) != null
}
