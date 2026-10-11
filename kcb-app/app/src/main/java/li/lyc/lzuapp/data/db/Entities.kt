package li.lyc.lzuapp.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 本地课程缓存。kch 唯一；自定义课程 kch 由本地生成。
 */
@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey val kch: String,
    val kcmc: String = "",
    val jsxm: String? = null,
    val jc: String = "00000000000000",
    val skjsl: String? = null,
    val skxql: Int = 1,
    val week: String = "",
    val bs: String = "1",
    val xykh: String? = null,
    val xn: String? = null,
    val xqm: Int? = null,
    val status: Int = 1,
    val color: String? = null,
    val skjc: String? = null,
    val xf: String? = null,
    val weekFb: String? = null,
    val kcrq: String? = null,
    /** 创建人学号（服务端自定义课程的 create_user_id） */
    val createUserId: String? = null,
    /** true = 用户自定义课程 */
    val isCustom: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "semester")
data class SemesterEntity(
    @PrimaryKey val id: Int = 1,
    val xn: String? = null,
    val xqm: String? = null,
    val xq: String? = null,
    val currentWeek: Int = 1,
    val totalWeeks: Int = 20,
    val semesterStart: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * 本地隐藏的系统课程（仅本机生效，不上传云端）。
 * 粒度：week = 0 表示隐藏全部周次；week = N 表示仅隐藏第 N 周（该课本周的那一节）。
 */
@Entity(tableName = "hidden_courses", primaryKeys = ["kch", "week"])
data class HiddenCourseEntity(
    val kch: String,
    val week: Int,
)
