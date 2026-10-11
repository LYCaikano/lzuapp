package li.lyc.lzuapp.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GatewayResponse<T>(
    val code: Int = 0,
    val message: String = "",
    val data: T? = null,
)

@Serializable
data class LoginData(
    @SerialName("login_token") val loginToken: String? = null,
    @SerialName("gateway_token") val gatewayToken: String? = null,
    @SerialName("reset_pwd_token") val resetPwdToken: String? = null,
    @SerialName("need_conf_security") val needConfSecurity: String? = null,
    val ways: List<String>? = null,
    @SerialName("session_id") val sessionId: String? = null,
)

@Serializable
data class UserInfoData(
    val xykh: String? = null,
    val xm: String? = null,
    @SerialName("dwmc") val college: String? = null,
    val yddh: String? = null,
    val dzxx: String? = null,
    val rylbm: String? = null,
)

@Serializable
data class AccessTokenData(
    @SerialName("tenant_id") val tenantId: Long? = null,
    val uid: String? = null,
    @SerialName("client_type") val clientType: Int? = null,
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("expires_in") val expiresIn: String? = null,
    @SerialName("user_name") val userName: String? = null,
)

@Serializable
data class XlxxData(
    /** 当前周次 */
    val dqrqszzc: String? = null,
    /** 总周数 */
    val zzs: String? = null,
    /** 学期开始日期 yyyyMMdd */
    val ksrq: String? = null,
    /** 学期序号 1春 2秋 3夏 */
    val xqm: String? = null,
    /** 学年 */
    val xn: String? = null,
    /** 学期名 */
    val xq: String? = null,
    val zcksxq: String? = null,
)

@Serializable
data class XtszXxData(
    @SerialName("xtsz_id") val xtszId: String? = null,
    val xykh: String? = null,
    /** 每周起始日 0 或 6 */
    val mzqsr: String? = null,
    val dqz: String? = null,
    @SerialName("dqz_bs") val dqzBs: Int? = null,
    /** 节数 */
    val szjs: String? = null,
    val xksj: String? = null,
    val sfbzkc: Int? = null,
    val ztdx: String? = null,
    val ztys: String? = null,
    val gzdx: String? = null,
    @SerialName("bjtp_url") val bjtpUrl: String? = null,
)

/**
 * 课程记录（来自 getKcXxByKcmc）
 */
@Serializable
data class CourseData(
    /** 课程号（雪花 ID） */
    val kch: String? = null,
    /** 课程名 */
    val kcmc: String? = null,
    /** 教师姓名 */
    val jsxm: String? = null,
    /** 14 位节次掩码，'1' = 该节有课 */
    val jc: String? = null,
    /** 上课教室 */
    val skjsl: String? = null,
    /** 星期几 1=周一 ... 7=周日 */
    val skxql: String? = null,
    /** 周次列表，逗号分隔，首尾带逗号 ",12,14," */
    val week: String? = null,
    /** 单双周标记 */
    val bs: String? = null,
    /** 学号 */
    val xykh: String? = null,
    /** 学年 */
    val xn: String? = null,
    /** 学期序号 */
    val xqm: Int? = null,
    val status: Int? = null,
    val color: String? = null,
    /** 人类可读节次 "周五，第7节 - 第8节" */
    val skjc: String? = null,
    /** 学分 */
    val xf: String? = null,
    /** 周次（无首尾逗号）"12,14" */
    @SerialName("week_fb") val weekFb: String? = null,
    /** 单次上课日期（补课用） */
    val kcrq: String? = null,
    @SerialName("create_time") val createTime: String? = null,
    @SerialName("create_user_id") val createUserId: String? = null,
)

@Serializable
data class AddCourseRequest(
    val kclsit: List<CourseData> = emptyList(),
)

@Serializable
data class WeekDatesData(
    val yi: String? = null,
    val er: String? = null,
    val san: String? = null,
    val si: String? = null,
    val wu: String? = null,
    val liu: String? = null,
    val qi: String? = null,
) {
    fun toDateList(): List<String> = listOfNotNull(yi, er, san, si, wu, liu, qi)
}

// ---------- 成绩查询（wdcj 命名空间，认证同课表） ----------

/** 有成绩的学期项。注意 xqm 语义与课表 getXlxx 不同：1=秋季 2=春季（勿混淆） */
@Serializable
data class StuXqItem(
    val xn: String? = null,
    val xqm: String? = null,
    val xq: String? = null,
    /** 完整学期名，如 "2026年春季学期" */
    val xqsj: String? = null,
)

/** 一条成绩记录（getWdcj 响应 data 以页码字符串为 key 的对象） */
@Serializable
data class GradeRecord(
    val kch: String? = null,
    /** 课程名 */
    val kcmc: String? = null,
    /** 考试学年 */
    val ksxn: String? = null,
    val xqm: String? = null,
    /** 学期名，如 "春季学期" */
    val ksxq: String? = null,
    /** 选课属性，如 "必修" */
    val xksx: String? = null,
    /** 考试性质，如 "正常考试" */
    val ksxz: String? = null,
    /** 学分 */
    val kcxf: String? = null,
    /** 平时/期中/期末成绩（可为 null 或等级） */
    val pscj: String? = null,
    val qzcj: String? = null,
    val qmcj: String? = null,
    /** 总评成绩（分数或等级） */
    val zpcj: String? = null,
    /** 绩点（5 分制） */
    val jd: String? = null,
    /** 学分积成绩（等级课换算成分数，算 GPA 用） */
    val xfjcj: String? = null,
    val jgbz: String? = null,
    val bz: String? = null,
    val px: String? = null,
    val rybh: String? = null,
)

// ---------- 邮箱（Coremail hxphone JSON API，POST /coremail/s/?func=..&sid=..） ----------

/** Coremail 响应壳：code 是字符串（"S_OK" 成功，"FA_" 前缀多为会话/权限问题） */
@Serializable
data class CoremailResponse<T>(
    val code: String = "",
    @SerialName("var") val result: T? = null,
)

@Serializable
data class MailFolder(
    val id: Int = 0,
    val name: String = "",
)

/** getFolderStats 返回 Map<fid字符串, 本结构> */
@Serializable
data class MailFolderStats(
    val messageCount: Int = 0,
    val unreadMessageCount: Int = 0,
)

@Serializable
data class MailFlags(
    val read: Boolean = false,
    val replied: Boolean = false,
    val attachment: Boolean = false,
)

/** 列表项（mbox:listMessages）：from/to 是裸字符串 */
@Serializable
data class MailMessage(
    val id: String = "",
    val fid: Int = 0,
    val from: String = "",
    val to: String = "",
    val subject: String = "",
    val sentDate: String = "",
    val receivedDate: String = "",
    val size: Long = 0,
    val flags: MailFlags? = null,
)

/** 正文部件：text/html 字段都是 {contentType, content} 结构 */
@Serializable
data class MailPart(
    val contentType: String = "",
    val content: String? = null,
)

@Serializable
data class MailAttachment(
    /** 附件名（Coremail 字段 attn；个别接口回 name） */
    val attn: String? = null,
    val name: String? = null,
    val size: Long = 0,
    val contentType: String = "",
) {
    val displayName: String get() = attn ?: name ?: "附件"
}

/** 邮件详情（mbox:readMessage）：from/to 是字符串数组 */
@Serializable
data class MailDetail(
    val from: List<String> = emptyList(),
    val to: List<String> = emptyList(),
    val subject: String = "",
    val sentDate: String = "",
    val html: MailPart? = null,
    val text: MailPart? = null,
    val attachments: List<MailAttachment> = emptyList(),
    val flags: MailFlags? = null,
) {
    /** 优先 html，其次纯文本 */
    val bodyHtml: String? get() = html?.content?.takeIf { it.isNotBlank() }
    val bodyText: String? get() = text?.content?.takeIf { it.isNotBlank() }
}

@Serializable
data class MailAttrs(
    val email: String = "",
    @SerialName("true_name") val trueName: String = "",
)
