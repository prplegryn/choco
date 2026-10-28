package app.chocola.data

import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter

/** Persist wall-clock recurrence separately from the next alarm; never derive it from completion time. */
data class ScheduledTask(
    val id: String = newId(), val sessionId: String = "", val title: String = "", val prompt: String = "",
    val frequency: String = "daily", val startAt: Long = System.currentTimeMillis() + 60_000,
    val zone: String = ZoneId.systemDefault().id, val intervalMinutes: Int = 60, val weekdays: Set<Int> = setOf(1),
    val endAt: Long = 0, val maxRuns: Int = 0, val timeoutMinutes: Int = 30, val enabled: Boolean = true,
    val nextAt: Long = 0, val runCount: Int = 0, val lastAt: Long = 0, val lastStatus: String = "", val lastDetail: String = "", val activeRun: String = "",
    val providerId: String = "", val access: Access = Access.ASK, val reasoningLevel: String = "", val modelSnapshot: String = "",
    val activeSessionId: String = "", val lastResultSessionId: String = "",
    val allowedTools: Set<String> = emptySet(), val credentialIds: Set<String> = emptySet(), val authorizationVersion: Int = 0
) {
    val resultSessionId: String get() = activeSessionId.ifBlank { lastResultSessionId.ifBlank { "scheduled:$id" } }
    fun json() = JSONObject().put("id", id).put("sessionId", sessionId).put("title", title).put("prompt", prompt)
        .put("frequency", frequency).put("startAt", startAt).put("zone", zone).put("intervalMinutes", intervalMinutes)
        .put("weekdays", jsonArray(weekdays.sorted())).put("endAt", endAt).put("maxRuns", maxRuns).put("timeoutMinutes", timeoutMinutes)
        .put("enabled", enabled).put("nextAt", nextAt).put("runCount", runCount).put("lastAt", lastAt).put("lastStatus", lastStatus).put("lastDetail", lastDetail).put("activeRun", activeRun)
        .put("providerId", providerId).put("access", access.name).put("reasoningLevel", reasoningLevel).put("modelSnapshot", modelSnapshot)
        .put("activeSessionId", activeSessionId).put("lastResultSessionId", lastResultSessionId)
        .put("allowedTools", jsonArray(allowedTools.sorted())).put("credentialIds", jsonArray(credentialIds.sorted())).put("authorizationVersion", authorizationVersion)
    fun withModel(session: Session, provider: Provider) = copy(sessionId = session.id, providerId = provider.id,
        access = session.access, reasoningLevel = ThinkingControls.selected(provider, session), modelSnapshot = provider.model)
    fun validate() {
        require(title.isNotBlank() && title.length <= 100) { "任务名称需为1–100字" }
        require(prompt.isNotBlank() && prompt.length <= 20000) { "请填写任务内容，最多20000字" }
        require(frequency in setOf("once", "interval", "daily", "weekly")) { "不支持的重复方式" }
        ZoneId.of(zone)
        require(startAt > 0 && (endAt == 0L || endAt >= startAt)) { "结束时间不能早于开始时间" }
        require(intervalMinutes in 15..525600) { "重复间隔需为15分钟至一年" }
        require(weekdays.isNotEmpty() && weekdays.all { it in 1..7 }) { "请选择有效的星期" }
        require(maxRuns in 0..100000 && timeoutMinutes in 1..1440) { "次数或单次运行时长无效" }
    }
    companion object {
        fun from(j: JSONObject) = ScheduledTask(id = j.getString("id"), sessionId = j.optString("sessionId"), title = j.optString("title"), prompt = j.optString("prompt"),
            frequency = j.optString("frequency", "daily"), startAt = j.getLong("startAt"), zone = j.optString("zone", ZoneId.systemDefault().id),
            intervalMinutes = j.optInt("intervalMinutes", 60), weekdays = j.optJSONArray("weekdays")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: setOf(1),
            endAt = j.optLong("endAt"), maxRuns = j.optInt("maxRuns"), timeoutMinutes = j.optInt("timeoutMinutes", 30), enabled = j.optBoolean("enabled", true),
            nextAt = j.optLong("nextAt"), runCount = j.optInt("runCount"), lastAt = j.optLong("lastAt"), lastStatus = j.optString("lastStatus"), lastDetail = j.optString("lastDetail"), activeRun = j.optString("activeRun"),
            providerId = j.optString("providerId"), access = runCatching { Access.valueOf(j.optString("access", "ASK")) }.getOrDefault(Access.ASK), reasoningLevel = j.optString("reasoningLevel"), modelSnapshot = j.optString("modelSnapshot"),
            activeSessionId = j.optString("activeSessionId"), lastResultSessionId = j.optString("lastResultSessionId"),
            allowedTools = j.optJSONArray("allowedTools")?.strings()?.toSet().orEmpty(), credentialIds = j.optJSONArray("credentialIds")?.strings()?.toSet().orEmpty(), authorizationVersion = j.optInt("authorizationVersion"))
    }
}

object ScheduleTimes {
    val dateTime: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT)
    fun display(at: Long, zone: String): String = if (at == 0L) "—" else Instant.ofEpochMilli(at).atZone(ZoneId.of(zone)).format(dateTime)
    fun parse(value: String, zone: String): Long = LocalDateTime.parse(value.trim(), dateTime).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()
    /** First occurrence strictly after [after], respecting start/end and execution count. 0 means finished. */
    fun next(task: ScheduledTask, after: Long): Long {
        if (!task.enabled || (task.maxRuns > 0 && task.runCount >= task.maxRuns)) return 0
        val start = task.startAt
        val candidate = when (task.frequency) {
            "once" -> if (start > after) start else 0
            "interval" -> {
                val interval = task.intervalMinutes.toLong() * 60_000
                if (start > after) start else start + ((after - start) / interval + 1) * interval
            }
            "daily", "weekly" -> {
                val zone = ZoneId.of(task.zone)
                val origin = Instant.ofEpochMilli(start).atZone(zone)
                var day = Instant.ofEpochMilli(maxOf(start, after)).atZone(zone).toLocalDate()
                var found = 0L
                repeat(9) {
                    val at = day.atTime(origin.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
                    if (found == 0L && at >= start && at > after && (task.frequency == "daily" || day.dayOfWeek.value in task.weekdays)) found = at
                    day = day.plusDays(1)
                }
                found
            }
            else -> 0
        }
        return candidate.takeIf { it > 0 && (task.endAt == 0L || it <= task.endAt) } ?: 0
    }
    fun claim(task: ScheduledTask, now: Long, outcome: String, runId: String): ScheduledTask {
        val active = task.activeRun.isNotBlank()
        val updated = task.copy(
            lastAt = if (active) task.lastAt else now,
            lastStatus = if (active) "running" else outcome,
            lastDetail = when {
                active -> "本次仍在执行，已跳过后续重叠时刻"
                outcome == "missed" -> "触发已迟到超过15分钟或有效期已结束，未补跑"
                outcome == "busy" -> "上一次执行尚未结束，已跳过本次"
                outcome == "failed" -> "任务模型已不可用，请重新选择模型"
                else -> ""
            },
            runCount = task.runCount + if (outcome == "running" && !active) 1 else 0,
            activeRun = if (outcome == "running" && !active) runId else task.activeRun,
            activeSessionId = if (outcome == "running" && !active) SessionIds.scheduled(task.id, runId) else task.activeSessionId,
            lastResultSessionId = if (outcome == "running" && !active) SessionIds.scheduled(task.id, runId) else task.lastResultSessionId)
        return updated.copy(nextAt = next(updated, now))
    }
    fun complete(task: ScheduledTask, runId: String, status: String): ScheduledTask = if (task.activeRun != runId) task else task.copy(
        activeRun = "", activeSessionId = "", lastStatus = status, lastDetail = if (status == "done") "执行结果已保存" else "请查看本次执行结果")
    fun summary(t: ScheduledTask): String = when (t.frequency) {
        "once" -> "仅执行一次"
        "interval" -> "每${t.intervalMinutes}分钟"
        "weekly" -> "每周" + t.weekdays.sorted().joinToString("、") { listOf("一", "二", "三", "四", "五", "六", "日")[it - 1] } + " " + display(t.startAt, t.zone).substringAfter(' ')
        else -> "每天 " + display(t.startAt, t.zone).substringAfter(' ')
    }
    fun status(value: String) = when (value) {
        "running" -> "执行中"; "done" -> "已完成"; "failed" -> "执行失败"; "timeout" -> "运行超时"
        "stopped" -> "已停止"; "limit" -> "达到执行轮数限制"; "missed" -> "已错过"; "busy" -> "上次未结束，已跳过"
        "interrupted" -> "执行中断"; else -> "尚未执行"
    }
}
