package li.lyc.lzuapp.data.api.model

import kotlinx.serialization.Serializable

/** jwqe 统一响应：成功 code==200（个别接口 0 也算），失败时 message 为错误文案 */
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
)

/** 评教课程（getXspjStudentCourses） */
@Serializable
data class EvalCourse(
    val id: Long = 0,
    val classno: String = "",
    val coursecode: String = "",
    val coursename: String = "",
    val courseorgname: String = "",
    val courseorgcode: String = "",
    val teachername: String = "",
    val jobnumber: String = "",
    /** 课程类型（理论课/实验课等），取题时服务端按它匹配指标体系 */
    val pjcoursetype: String = "",
    val yearterm: Int = 0,
    val studentid: String = "",
    val studentname: String = "",
    /** 已评/暂存才有值（提交结果 id），未评为 null */
    val pjjgid: Long? = null,
    /** 0=未评 1=已评 2=暂存 */
    val hassubmit: Int = 0,
    val pjjgtime: String? = null,
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
)

/** saveStudentComment 提交条目（每元素一门课）；tevaluateResultid 新提交=0，修改已评=原 pjjgid */
@Serializable
data class EvalSubmitItem(
    /** 合计分（字符串） */
    val totalscore: String = "0",
    val classno: String = "",
    val coursecode: String = "",
    val coursename: String = "",
    val jobnumber: String = "",
    val tevaluateResultid: Long = 0,
    val taskid: String = "",
    val studentid: String = "",
    val studentname: String = "",
    val teachername: String = "",
    val yearterm: String = "",
    val courseorgname: String = "",
    val courseorgcode: String = "",
    val pjcoursetype: String = "",
    /** yyyy-MM-dd HH:mm:ss */
    val commit_time: String = "",
    val evaluateResult: List<EvalSubmitAnswer> = emptyList(),
)

/**
 * 单条作答：id 新提交=0/回填=已有答案 id；index_order=题目 ordor；sfbt=题目 isemptyed；
 * index_score 单选=选中项 score、问答=0；index_title 编码同 [EvalAnswer]；option_id 单选=选项 id、其他题型=0。
 */
@Serializable
data class EvalSubmitAnswer(
    val id: Long = 0,
    val indexid: String = "",
    val index_order: Int = 0,
    val index_type: String = "",
    val sfbt: String = "",
    val index_score: Double = 0.0,
    val index_title: String = "",
    val option_id: String = "0",
)
