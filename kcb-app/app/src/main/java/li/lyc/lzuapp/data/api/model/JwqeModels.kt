package li.lyc.lzuapp.data.api.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** jwqe 统一响应：读接口成功 code==200（个别接口 0 也算），失败时 message 为错误文案 */
@Serializable
data class JwqeResponse<T>(
    val code: Int = -1,
    val message: String = "",
    val data: T? = null,
)

/** 列表接口的 data 包装（pageData 数组） */
@Serializable
data class JwqePage<T>(val pageData: List<T> = emptyList())

@Serializable
data class JwqeRecords<T>(val records: List<T> = emptyList())

/** 教师列表接口的 data.pageData.records 包装 */
@Serializable
data class JwqeRecordPage<T>(val pageData: JwqeRecords<T> = JwqeRecords())

@Serializable
data class JwqeLoginData(val accessToken: String = "")

/** 评教任务（getXspjtask） */
@Serializable
data class EvalTask(
    val taskid: Long = 0,
    val taskname: String = "",
    val yearterm: Int = 0,
    val starttime: String = "",
    val endtime: String = "",
    /** 未开始/进行中/已结束 */
    val currentStatus: String = "",
    /** 指标体系 id，可能逗号分隔多个（如 "98,97,96"），取题时原样传给 getXspjTindexSystem */
    val indexid: String = "",
    /** 评教方式："打分"/"排名打分"（排名打分暂不支持，字段保留供 UI 提示） */
    val pjfs: String = "",
    val remark: String = "",
    /** 是否可重复评/暂存："是"/"否" */
    val sfkydcpj: String = "",
    val sfqxzdf: String = "",
    val sfqxzdzgf: String = "",
    val objecttask: String = "",
    val taskfbfs: String = "",
    val hjjstask: Int? = null,
)

/** 普通课程或合讲课程/教师的统一表单模型 */
@Serializable
data class EvalCourse(
    val id: Long = 0,
    val classno: String? = null,
    val coursecode: String = "",
    val coursename: String = "",
    val courseorgname: String = "",
    val courseorgcode: String = "",
    val teachername: String = "",
    val jobnumber: String? = null,
    /** 课程类型（理论课/实验课等），取题时服务端按它匹配指标体系 */
    val pjcoursetype: String = "",
    val yearterm: Int = 0,
    val studentid: String = "",
    val studentname: String = "",
    /** 已评/暂存才有非零值（提交结果 id），未评可能为 null 或 0 */
    val pjjgid: Long? = null,
    /** 0=未评 1=已评 2=暂存 */
    val hassubmit: Int = 0,
    val pjjgtime: String? = null,
    /** 空=普通课程；hjjskc=合讲课程；hjjspj=合讲教师 */
    val pjtype: String = "",
    /** 合讲教师总人数 */
    val hjjss: Int? = null,
    /** 合讲教师已评人数，用于课程卡片的展示进度 */
    val speaks: Int? = null,
    val speakstates: Int? = null,
    val joblist: List<String>? = null,
) {
    /** 课程或教师自身的评价状态；表单只读和提交后刷新仍以此为准 */
    val isEvaluated: Boolean
        get() = if (pjtype == "hjjspj") {
            (pjjgid ?: 0L) != 0L && speakstates == 2
        } else {
            hassubmit == 1
        }

    /** 展示用剩余教师数；null 表示服务端未提供完整进度，不能据此认定已完成 */
    val remainingTeachers: Int?
        get() = if (pjtype == "hjjskc") {
            val total = hjjss
            val evaluated = speaks
            if (total == null || evaluated == null) null else (total - evaluated).coerceAtLeast(0)
        } else {
            0
        }

    /** 仅用于列表展示：普通课程看自身，合讲课程还要求剩余教师数为 0 */
    val isComplete: Boolean
        get() = when (pjtype) {
            "hjjskc" -> isEvaluated && remainingTeachers == 0
            else -> isEvaluated
        }
}

/** 合讲教师查询（getJobnumberEvaluation?a=1，JSON object body） */
@Serializable
data class EvalTeacherQuery(
    val taskid: Long,
    val yearterm: Int,
    val coursecode: String,
    val page: Int,
    val limit: Int,
    val pjcoursetype: String,
)

@Serializable
data class EvalTeacherRecord(
    val teachername: String = "",
    val jobnumber: String? = null,
    val pjjgid: Long? = null,
    val speakstates: Int? = null,
)

/** 评教题目（getXspjTindexSystem） */
@Serializable
data class EvalQuestion(
    val systemid: Long = 0,
    val indexid: Long = 0,
    /** 一级指标分组名 */
    val firstlevlindex: String = "",
    val firstlevlindexid: Long = 0,
    /** 单选题/多选题/打分题/填空题/问答题 */
    val type: String = "",
    val title: String = "",
    val remark: String = "",
    /** 满分基数 */
    val score: Double = 0.0,
    val weight: Double = 0.0,
    val zf: Double = 0.0,
    /** 排序 */
    val ordor: Int = 0,
    /** "否"=必答 */
    val isemptyed: String = "",
    /** "是"=计分 */
    val isscored: String = "",
    val scoring_type: Int = 0,
    /** 选择题选项 */
    val optionarr: List<EvalOption> = emptyList(),
)

@Serializable
data class EvalOption(
    val id: Long = 0,
    val title: String = "",
    val score: Double = 0.0,
)

/**
 * 已提交答案（getevaluateResultId）。
 * index_title 编码：单选=选项 title；多选=多个 title 以 * 连接；填空=各空答案以 * 连接；打分=分数数字符串。
 */
@Serializable
data class EvalAnswer(
    val id: Long = 0,
    val indexid: Long = 0,
    val index_score: Double = 0.0,
    val index_title: String = "",
    val option_id: Long = 0,
)

/** 三种评教保存接口共用的提交条目；普通提交省略 speakstates，合讲课程=1，合讲教师=2。 */
@Serializable
data class EvalSubmitItem(
    /** 零为数值 0，非零为保留两位小数的字符串 */
    val totalscore: JsonElement,
    val classno: String?,
    val coursecode: String,
    val coursename: String,
    val jobnumber: String?,
    /** 表单 speakstates==1 或 op==dcpj 时可为恢复的非零 pjjgid 或 null，否则为 0 */
    val tevaluateResultid: Long?,
    val taskid: Long,
    val studentid: String,
    val studentname: String,
    val teachername: String,
    val yearterm: Int,
    val courseorgname: String,
    val courseorgcode: String,
    val pjcoursetype: String,
    /** yyyy-MM-dd HH:mm:ss */
    val commit_time: String,
    val evaluateResult: List<EvalSubmitAnswer>,
    val speakstates: Int? = null,
)

/**
 * 单条作答：id 新提交=0/回填=已有答案 id；index_order=题目 ordor；sfbt=题目 isemptyed；
 * index_score 单选=选中项 score、问答=0；option_id 单选为字符串，问答为数值 0。
 */
@Serializable
data class EvalSubmitAnswer(
    val id: Long,
    val indexid: Long,
    val index_order: Int,
    val index_type: String,
    val sfbt: String,
    val index_score: Double,
    val index_title: String,
    val option_id: JsonElement,
)
