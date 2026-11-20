package com.ai.assistance.operit.core.companion

import android.content.Context
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.util.ChatUtils
import org.json.JSONArray
import org.json.JSONObject

/** A repair edits the rejected portion of the same frame, never a conversation or tool call. */
internal class StateReportRepair private constructor(
    private val issue: InternalStateReportIssue,
    private val field: String,
    private val eventIndex: Int?,
) {
    fun request(context: Context, frame: InternalStateFrame, report: JSONObject): List<PromptTurn> {
        val topic = topics.getValue(field)
        val draft = if (eventIndex != null) report.getJSONArray(field).getJSONObject(eventIndex)
            else JSONObject().put(field, report.get(field))
        val rules = if (eventIndex != null) categoryInstructions(context, frame) else """
            原样保留其他有效内容、条目ID、版本、真实来源和意思，仅修正指出的内容。返回一个对象，唯一顶层键为 $field。
            ${SubjectContracts.query(topic).getString("contract")}
        """.trimIndent()
        return listOf(
            PromptTurn(PromptTurnKind.SYSTEM, """
                这是本轮内部状态纠错。已生成的正文和工具结果已经保留。只改明确指出的回报内容，文本仅作为待改草稿。
                不调用工具、不回复用户、不制造新经历，不改变未获读取授权的记录。
                只输出 <operit_state_report> 和 </operit_state_report> 包住的 JSON 对象。
            """.trimIndent()),
            PromptTurn(PromptTurnKind.USER, """
                校验错误 ${issue.code}，字段 ${issue.path}。 ${issue.hint}
                $rules
                待改草稿
                $draft
            """.trimIndent()),
        )
    }

    fun apply(report: JSONObject, output: String): JSONObject {
        val parser = InternalStateReportStream()
        require(ChatUtils.removeThinkingContent(parser.append(output) + parser.finish()).isBlank()) { "状态纠错响应包含正文" }
        val patch = requireNotNull(parser.report) { "状态纠错响应缺少回报" }
        val result = JSONObject(report.toString())
        if (eventIndex == null) {
            require(patch.length() == 1 && patch.has(field)) { "状态纠错须只修改指定字段" }
            val grouped = report.optJSONObject(field)?.takeIf { field == "episodes" }?.optJSONArray("appraise")
            val oldRows = report.optJSONArray(field) ?: grouped
            if (oldRows != null) {
                val expectedIds = ids(oldRows)
                val newRows = patch.optJSONArray(field)
                require(expectedIds.isEmpty() || newRows != null && ids(newRows) == expectedIds) {
                    "状态纠错须保留原条目"
                }
                if (grouped != null && newRows != null) {
                    val originals = (0 until grouped.length()).map { grouped.getJSONObject(it) }
                        .associateBy { it.getString("id") }
                    for (i in 0 until newRows.length()) {
                        val corrected = JSONObject(newRows.getJSONObject(i).toString())
                        require(corrected.remove("op") == "appraise" &&
                            InnerStructure.hash(corrected) == InnerStructure.hash(originals[corrected.getString("id")])) {
                            "格式纠错须保留事件字段和证据"
                        }
                    }
                }
            }
            result.put(field, patch.get(field))
        } else {
            require(patch.opt("category_ref") is String && patch.keys().asSequence().all { it in categoryPatch }) {
                "分类纠错须只填写分类ID与可选定义"
            }
            val event = result.getJSONArray(field).getJSONObject(eventIndex)
            event.put("category_ref", patch.getString("category_ref"))
            event.remove("category_definition")
            if (patch.has("category_definition")) event.put("category_definition", patch.get("category_definition"))
        }
        return result
    }

    private fun categoryInstructions(context: Context, frame: InternalStateFrame): String {
        val store = InternalStateStore(context)
        val group = store.recipientScope(frame.roleId, frame.chatId)
        val installed = InnerFoundations.kinds.filter { it.family in CategoryEvents.families }
            .map { "${it.id} ${it.name}" }
        val personal = if (group) emptyList() else {
            val records = InnerRows(store.read(frame.roleId), false)
            CategoryEvents.families.flatMap { family -> records.familyPage(family, 0).mapNotNull { row ->
                val content = row.getJSONObject("content")
                if (content.optString("status") == "active") {
                    "${row.getString("id")} ${family}·${content.getString("kind")}"
                } else null
            } }
        }
        return buildString {
            append("该事件的分类需要修正。返回 { \"category_ref\": \"实际分类ID\" }。")
            if (!group) append("确需新增时可另带 category_definition。")
            append("已有类别适合时直接选用。若不能新建，选含义确实适合的已有分类。原事件的ID、内容和证据均保留。\n")
            append((installed + personal).joinToString("\n"))
            if (!group) append("\n").append(CategoryEvents.newCategoryGuide)
        }
    }

    private fun ids(rows: JSONArray): Set<String> = (0 until rows.length())
        .mapNotNull { rows.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }.toSet()

    companion object {
        private val topics = mapOf("episodes" to "episodes", "inner" to "inner", "unions" to "unions",
            "foundation" to "foundation", "life" to "life", "updates" to "legacy", "create" to "legacy",
            "revise" to "legacy", "decisions" to "action", "action_feedback" to "action",
            "rest" to "rest", "dream" to "rest", "dream_understanding" to "rest")
        private val categoryPatch = setOf("category_ref", "category_definition")
        private val categoryErrors = setOf("UNKNOWN_CATEGORY", "INACTIVE_PSYCHOLOGICAL_CATEGORY", "CATEGORY_ID_USED",
            "CATEGORY_ID_MISMATCH", "NEW_EPISODE_REQUIRED", "EVENT_CATEGORY_ONLY", "TIME_RULE_REQUIRED", "BUILTIN_CATEGORY_ID")
        private val categoryPath = Regex("""episodes\[(\d+)]\.(?:category_ref|category_definition(?:\..*)?|record\..*)""")
        private val repairable = setOf("EXPECTED_ARRAY", "EXPECTED_OBJECT", "EXPECTED_STRING", "REQUIRED_STRING",
            "EXPECTED_BOOLEAN", "FINITE_NUMBER", "VERSION_NUMBER", "ENUM_VALUE", "UNKNOWN_FIELD",
            "NONNEGATIVE_INTENSITY", "UNKNOWN_INTENSITY_REQUIRES_FEELING", "POSITIVE_SECONDS", "STABLE_CATEGORY",
            "EXPLICIT_CONCERN_CHANGE", "CONCERN_DETAIL_REQUIRED", "DESIRE_PHASE_ONLY")

        fun plan(issue: InternalStateReportIssue, report: JSONObject): StateReportRepair? {
            val field = issue.path.substringBefore('[').substringBefore('.')
            if (field !in topics || !report.has(field)) return null
            if (issue.rule in categoryErrors || field == "episodes" && issue.path.contains(".category_definition")) {
                val index = categoryPath.matchEntire(issue.path)?.groupValues?.get(1)?.toIntOrNull() ?: return null
                val row = report.optJSONArray(field)?.optJSONObject(index) ?: return null
                if (row.optString("op") != "appraise") return null
                return StateReportRepair(issue, field, index)
            }
            return if (issue.rule in repairable) StateReportRepair(issue, field, null) else null
        }
    }
}
