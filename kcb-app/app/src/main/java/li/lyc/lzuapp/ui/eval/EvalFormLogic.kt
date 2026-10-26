package li.lyc.lzuapp.ui.eval

import kotlinx.serialization.json.JsonPrimitive
import li.lyc.lzuapp.data.api.model.EvalOption
import li.lyc.lzuapp.data.api.model.EvalQuestion
import li.lyc.lzuapp.data.api.model.EvalSubmitAnswer

private data class DowngradeCandidate(val indexid: Long, val optionId: Long)

/** Shared scoring rules for individual forms and batch evaluation. */
internal object EvalFormLogic {
    fun autoFill(current: EvalUiState): EvalUiState {
        val task = current.task ?: return current
        if (current.readonly || current.submitting || current.hasUnsupported) return current
        val s = current.copy(submitError = null)
        val scored = s.questions.filter { it.isscored == "是" && it.type == "单选题" }
        if (scored.isEmpty()) {
            return s.copy(message = "当前没有可自动填写的计分单选题")
        }
        if (scored.any { it.optionarr.isEmpty() }) {
            return s.copy(message = "部分计分单选题没有可用选项，无法自动填写")
        }

        val selected = scored.associate { q ->
            q.indexid to requireNotNull(q.optionarr.maxByOrNull { it.score }).id
        }.toMutableMap()
        var downgradeCount = 0
        if (task.sfqxzdzgf == "是") {
            val candidates = scored.mapNotNull { q ->
                val highest = requireNotNull(q.optionarr.maxByOrNull { it.score })
                val second = q.optionarr.filter { it.score < highest.score }.maxByOrNull { it.score }
                second?.let { DowngradeCandidate(q.indexid, it.id) }
            }
            if (candidates.size < 2) {
                return s.copy(
                    message = "可用于降档的计分单选题不足2道（当前${candidates.size}道），无法一键高分",
                )
            }
            downgradeCount = if (candidates.size == 2) 2 else (2..3).random()
            candidates.shuffled().take(downgradeCount).forEach { candidate ->
                selected[candidate.indexid] = candidate.optionId
            }
        }
        if (task.sfqxzdf == "是" && isAllMinimum(scored, selected)) {
            return s.copy(
                message = if (downgradeCount > 0) {
                    "本次随机降档${downgradeCount}道后会触发全最低分限制，未应用自动填写，请重试或手动调整"
                } else {
                    "当前题目最高分与最低分相同，无法按规则一键满分"
                },
            )
        }

        val comments = s.questions.asSequence()
            .filter { it.type == "问答题" }
            .associate { it.indexid to defaultComment(it.title) }
        return s.copy(
            singleAnswers = s.singleAnswers + selected,
            textAnswers = s.textAnswers + comments,
            message = null,
        )
    }

    private fun defaultComment(title: String): String {
        val asksForSuggestion = listOf("建议", "改进", "提高", "不足", "问题", "意见").any(title::contains)
        return if (asksForSuggestion) {
            "无"
        } else {
            "教师备课充分，讲解清晰，课程内容安排合理，能够耐心解答问题，并结合实际帮助理解，我在本课程中收获很大。"
        }
    }

    private fun selectedOption(q: EvalQuestion, answers: Map<Long, Long>): EvalOption? =
        q.optionarr.firstOrNull { it.id == answers[q.indexid] }

    private fun isAllMaximum(questions: List<EvalQuestion>, answers: Map<Long, Long>): Boolean =
        questions.isNotEmpty() && questions.all { q ->
            val selected = selectedOption(q, answers) ?: return@all false
            val maximum = q.optionarr.maxOfOrNull { it.score } ?: return@all false
            selected.score == maximum
        }

    private fun isAllMinimum(questions: List<EvalQuestion>, answers: Map<Long, Long>): Boolean =
        questions.isNotEmpty() && questions.all { q ->
            val selected = selectedOption(q, answers) ?: return@all false
            val minimum = q.optionarr.minOfOrNull { it.score } ?: return@all false
            selected.score == minimum
        }

    fun validate(s: EvalUiState): EvalUiState {
        val task = requireNotNull(s.task)
        if (s.hasUnsupported) {
            return s.copy(submitError = "包含暂不支持的题型，请前往官方 App 完成评价")
        }

        val firstMissing = s.questions.firstOrNull { q ->
            if (q.isemptyed != "否") return@firstOrNull false
            when (q.type) {
                "单选题" -> selectedOption(q, s.singleAnswers) == null
                "问答题" -> s.textAnswers[q.indexid].isNullOrBlank()
                else -> false
            }
        }
        if (firstMissing != null) {
            return s.copy(
                submitError = "还有必答题未作答，请检查带 * 的题目",
                scrollToQuestion = firstMissing.indexid,
            )
        }

        val scored = s.questions.filter { it.isscored == "是" && it.type == "单选题" }
        if (task.sfqxzdzgf == "是" && isAllMaximum(scored, s.singleAnswers)) {
            return s.copy(submitError = "评价不能全选最高分！")
        }
        if (task.sfqxzdf == "是" && isAllMinimum(scored, s.singleAnswers)) {
            return s.copy(submitError = "评价不能全选最低分！")
        }

        return s
    }

    fun answers(s: EvalUiState): List<EvalSubmitAnswer> {
        return s.questions.map { q ->
            when (q.type) {
                "单选题" -> {
                    val opt = selectedOption(q, s.singleAnswers)
                    EvalSubmitAnswer(
                        id = s.answerIds[q.indexid] ?: 0,
                        indexid = q.indexid,
                        index_order = q.ordor,
                        index_type = q.type,
                        sfbt = q.isemptyed,
                        index_score = opt?.score ?: 0.0,
                        index_title = opt?.title.orEmpty(),
                        option_id = JsonPrimitive(opt?.id?.toString().orEmpty()),
                    )
                }
                else -> EvalSubmitAnswer(
                    id = s.answerIds[q.indexid] ?: 0,
                    indexid = q.indexid,
                    index_order = q.ordor,
                    index_type = q.type,
                    sfbt = q.isemptyed,
                    index_score = 0.0,
                    index_title = s.textAnswers[q.indexid].orEmpty(),
                    option_id = JsonPrimitive(0),
                )
            }
        }
    }
}
