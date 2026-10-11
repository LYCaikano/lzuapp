package li.lyc.lzuapp.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {
    @Query("SELECT * FROM courses ORDER BY skxql, jc")
    fun observeAll(): Flow<List<CourseEntity>>

    /** 把缺学期信息的本地自定义课补到指定学期（历史遗留数据迁移） */
    @Query("UPDATE courses SET xn = :xn, xqm = :xqm WHERE isCustom = 1 AND (xn IS NULL OR xqm IS NULL)")
    suspend fun backfillCustomSemester(xn: String, xqm: Int)

    @Query("SELECT * FROM courses WHERE isCustom = 1")
    suspend fun getCustomCourses(): List<CourseEntity>

    @Query("SELECT * FROM courses")
    suspend fun getAll(): List<CourseEntity>

    @Upsert
    suspend fun upsertAll(courses: List<CourseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(course: CourseEntity)

    @Delete
    suspend fun delete(course: CourseEntity)

    @Query("SELECT * FROM courses WHERE kch = :kch")
    suspend fun getByKch(kch: String): CourseEntity?

    @Query("DELETE FROM courses")
    suspend fun deleteAll()

    @Query("DELETE FROM courses WHERE kch = :kch")
    suspend fun deleteByKch(kch: String)
}

@Dao
interface SemesterDao {
    @Query("SELECT * FROM semester WHERE id = 1")
    fun observe(): Flow<SemesterEntity?>

    @Query("SELECT * FROM semester WHERE id = 1")
    suspend fun get(): SemesterEntity?

    @Upsert
    suspend fun upsert(semester: SemesterEntity)

    @Query("DELETE FROM semester")
    suspend fun clear()
}

@Dao
interface HiddenCourseDao {
    @Query("SELECT * FROM hidden_courses")
    fun observeAll(): Flow<List<HiddenCourseEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<HiddenCourseEntity>)

    @Query("DELETE FROM hidden_courses WHERE kch = :kch AND week = :week")
    suspend fun delete(kch: String, week: Int)

    @Query("DELETE FROM hidden_courses WHERE kch = :kch")
    suspend fun deleteByKch(kch: String)
}

@Database(
    entities = [CourseEntity::class, SemesterEntity::class, HiddenCourseEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun courseDao(): CourseDao
    abstract fun semesterDao(): SemesterDao
    abstract fun hiddenCourseDao(): HiddenCourseDao

    companion object {
        /** v1 -> v2：courses 表新增 createUserId 列（服务端自定义课识别） */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE courses ADD COLUMN createUserId TEXT")
            }
        }

        /** v2 -> v3：新增 hidden_courses 表（本地隐藏系统课程，week=0 为全部周） */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `hidden_courses` " +
                        "(`kch` TEXT NOT NULL, `week` INTEGER NOT NULL, PRIMARY KEY(`kch`, `week`))",
                )
            }
        }
    }
}
