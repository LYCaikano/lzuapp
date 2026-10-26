package li.lyc.lzuapp.data.repo

import li.lyc.lzuapp.data.api.model.EvalTeacherRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class EvalTeacherPageTest {
    @Test fun overlappingPagesDeduplicateByJobNumberAndPreserveEvaluatedStatus() {
        val records = linkedMapOf<Pair<String?, String>, EvalTeacherRecord>()
        val pending = EvalTeacherRecord("张老师", "T1")
        val evaluated = pending.copy(pjjgid = 10, speakstates = 2)
        assertEquals(1, mergeTeacherPage(records, listOf(pending, evaluated)))
        assertEquals(1, mergeTeacherPage(records, listOf(pending.copy(teachername = "张"), EvalTeacherRecord("李老师", "T2"))))
        assertEquals(0, mergeTeacherPage(records, listOf(evaluated.copy(speakstates = 1))))
        assertEquals(2, records.size)
        assertEquals(2, records["T1" to ""]?.speakstates)
    }
}
