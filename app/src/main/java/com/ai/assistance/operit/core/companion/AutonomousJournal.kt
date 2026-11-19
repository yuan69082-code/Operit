package com.ai.assistance.operit.core.companion

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Durable technical receipts, separate from psychological state and private model notes. */
class AutonomousJournal(private val context: Context) {
    private val store = CompanionStore(context)
    internal data class Run(val role: String, val chat: String, val id: String, @Volatile var toolsTouched: Boolean = false,
        @Volatile var responseCompleted: Boolean = false)

    fun page(role: String, offset: Int = 0): JSONObject {
        require(offset >= 0)
        val rows = store.activityPage(role, "runtime_run", "private", offset)
        return JSONObject().put("items", JSONArray(rows.take(8)))
            .put("next_offset", if (rows.size > 8) offset + 8 else JSONObject.NULL)
    }

    internal fun diagnostic(role: String, stage: String, failure: Exception) {
        val run = Run(role, "", java.util.UUID.randomUUID().toString())
        store.activityWrite(role, "runtime_run", run.id, "private") {
            JSONObject().put("id", run.id).put("parent", "private").put("owner", role).put("source_chat", "")
                .put("activity", "调度检查").put("source", stage).put("attempt", 1).put("at", System.currentTimeMillis())
        }
        update(run, "skipped", "本次检查未完成；保留原记录，继续其他活动", failure)
    }

    private fun update(run: Run, status: String, detail: String, failure: Throwable? = null) {
        store.activityWrite(run.role, "runtime_run", run.id, "private") { old ->
            requireNotNull(old).put("status", status).put("detail", detail).put("updated_at", System.currentTimeMillis())
                .put("tools_touched", run.toolsTouched).put("response_completed", run.responseCompleted).apply {
                    val steps = optJSONArray("steps") ?: JSONArray()
                    steps.put(JSONObject().put("at", System.currentTimeMillis()).put("status", status).put("detail", detail))
                    put("steps", steps)
                    if (failure != null) put("error_type", failure.javaClass.simpleName)
                        .put("error", failure.message.orEmpty().take(2000))
                        .put("stack", AppLogger.getStackTraceString(failure))
                }
        }
        AppLogger.i("AutonomousRuntime", "run=${run.id} status=$status $detail")
        if (failure != null) AppLogger.e("AutonomousRuntime", "run=${run.id} $status", failure)
    }

    internal suspend fun <T> execute(role: String, chat: String, activity: String, source: String,
        retryAllowed: Boolean = true, pause: suspend (Long) -> Unit = { delay(it) }, block: suspend () -> T): T {
        val chain = java.util.UUID.randomUUID().toString()
        val control = AutonomousRuntimeControl(context)
        for (attempt in 1..2) {
            currentCoroutineContext().ensureActive()
            val run = Run(role, chat, "$chain:$attempt")
            store.activityWrite(role, "runtime_run", run.id, "private") {
                JSONObject().put("id", run.id).put("parent", "private").put("chain", chain).put("owner", role).put("source_chat", chat)
                    .put("activity", activity).put("source", source).put("attempt", attempt)
                    .put("at", System.currentTimeMillis()).put("status", "preparing").put("detail", "准备活动")
            }
            check(active.putIfAbsent(chat, run) == null) { "此窗口已有自主活动日志" }
            var began = false
            var success = false
            var interrupted = false
            var retry = false
            try {
                control.begin(role, activity); began = true
                val result = block()
                success = true
                update(run, "completed", "本轮已结束；活动整体进度以原计划和回执为准")
                return result
            } catch (deferred: SubjectLife.Deferred) {
                interrupted = true
                update(run, "deferred", "已交接或按休息规则暂存，未执行本机判断")
                throw deferred
            } catch (cancelled: CancellationException) {
                interrupted = cancelled !is TimeoutCancellationException
                update(run, if (interrupted) "interrupted" else "skipped",
                    if (interrupted) "用户交互、设置变更或服务停止；保留进展" else "活动超时；保留回执，继续其他活动", cancelled)
                throw cancelled
            } catch (failure: Exception) {
                retry = retryAllowed && canRetry(failure, run.toolsTouched || run.responseCompleted, attempt) && control.allowed()
                update(run, if (retry) "retry_wait" else "skipped", when {
                    retry -> "尚未调用工具；5秒后重试一次当前模型请求"
                    run.responseCompleted -> "模型响应已结束，状态可能已采用；保留进展，不整轮重试，继续其他活动"
                    run.toolsTouched -> "已有工具尝试，不能重放未知副作用；保留回执并继续其他活动，续接前先核对"
                    attempt == 2 -> "重试仍失败；跳过本次，继续其他活动"
                    else -> "此错误不适合自动重试；保留原因并继续其他活动"
                }, failure)
                if (!retry) throw failure
            } finally {
                active.remove(chat, run)
                if (began) control.finish(role, success, interrupted)
            }
            if (retry) {
                try { pause(5000); update(run, "retried", "已进入第2次尝试；与下一条记录属于同一活动") }
                catch (cancelled: CancellationException) {
                    update(run, "interrupted", "等待重试时被取消，没有再次调用模型", cancelled)
                    throw cancelled
                }
            }
        }
        error("自主重试次数越界")
    }

    internal fun recover(role: String, activity: String? = null) {
        val rows = store.activityPage(role, "runtime_run", "private", 0)
        rows.filter { (activity == null || it.optString("activity") == activity) && it.optString("status") in setOf("preparing", "running", "retry_wait") }.forEach { row ->
            update(Run(role, row.getString("source_chat"), row.getString("id"), row.optBoolean("tools_touched")),
                "interrupted", "应用上次退出；原操作结果待核对，不自动重放。其他活动可继续")
        }
    }

    suspend fun exportFile(): java.io.File {
        val file = java.io.File(context.cacheDir, "autonomous-runs.jsonl")
        file.bufferedWriter().use { out ->
            for (card in com.ai.assistance.operit.data.preferences.CharacterCardManager.getInstance(context).getAllCharacterCards()) {
                var offset = 0
                while (true) {
                    val page = page(card.id, offset)
                    val rows = page.getJSONArray("items")
                    for (i in 0 until rows.length()) { out.write(rows.getJSONObject(i).toString()); out.newLine() }
                    if (page.isNull("next_offset")) break
                    offset = page.getInt("next_offset")
                }
            }
        }
        return file
    }

    companion object {
        private val active = ConcurrentHashMap<String, Run>()
        internal fun canRetry(failure: Exception, toolsTouched: Boolean, attempt: Int): Boolean =
            !toolsTouched && attempt < 2 && (failure is java.io.IOException ||
                failure is InternalStateReportStream.ProtocolException)
        internal fun progress(context: Context, chat: String?, phase: String, tool: String? = null) {
            val run = active[chat ?: return] ?: return
            if (tool != null) run.toolsTouched = true
            AutonomousJournal(context).update(run, "running", if (tool == null) phase else "$phase：$tool")
        }
        internal fun responseCompleted(context: Context, chat: String) {
            val run = active[chat] ?: return
            run.responseCompleted = true
            AutonomousJournal(context).update(run, "running", "模型响应已结束，正在核对和保存结果")
        }
    }
}

/** The report may already be adopted before envelope validation; never replay this round. */
internal class AutonomousResponseException(message: String) : IllegalStateException(message)
