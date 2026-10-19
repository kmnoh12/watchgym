package dev.kyungmin.kcoach.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutDataTest {
    @Test
    fun `home routines preserve the requested four split order`() {
        assertEquals(
            listOf("어깨전문", "등·이두", "가슴·삼두", "팔·하체"),
            WorkoutData.defaultRoutines().map { it.name },
        )
    }

    @Test
    fun `routine exercises preserve the observed workout order`() {
        val routines = WorkoutData.defaultRoutines().associateBy { it.id }
        assertEquals(
            listOf(
                "라잉 덤벨 풀오버",
                "바벨 플랫 벤치 프레스",
                "바벨 인클라인 벤치 프레스",
                "머신 플라이",
                "케이블 트라이셉스 푸시 다운",
                "케이블 오버헤드 트라이셉 익스텐션",
            ),
            routines.getValue("chest_triceps").exercises.map { it.exercise.name },
        )
        assertEquals("풀업", routines.getValue("arms_legs").exercises.first().exercise.name)
        assertEquals("레그 익스텐션", routines.getValue("arms_legs").exercises.last().exercise.name)
    }

    @Test
    fun `exercise catalog is normalized and category ready`() {
        val ids = WorkoutData.catalog.map { it.id }
        assertEquals(202, ids.size)
        assertEquals(ids.size, ids.distinct().size)
        WorkoutData.catalog.forEach { exercise ->
            assertTrue(exercise.id, exercise.bodyCategory in dev.kyungmin.kcoach.core.ExerciseTaxonomy.bodyOrder)
            assertTrue(exercise.id, exercise.equipment in setOf("맨몸", "덤벨", "바벨", "머신", "케이블", "기타"))
            assertTrue(exercise.id, dev.kyungmin.kcoach.core.ExerciseTaxonomy.section(
                exercise.bodyCategory, exercise.pattern).first != dev.kyungmin.kcoach.core.ExerciseTaxonomy.OTHER_ORDER)
        }
        assertTrue(WorkoutData.catalog.all { it.bodyCategory.isNotBlank() && it.equipment.isNotBlank() })
        assertFalse(WorkoutData.catalog.any { it.name == "풀 업" })
        assertTrue(WorkoutData.catalog.any { it.name == "풀업" })
    }

    @Test
    fun `warmups use W and do not consume work set numbers`() {
        val sets = listOf(
            PlannedSet(5.0, 20, setType = SetType.WARMUP),
            PlannedSet(10.0, 12),
            PlannedSet(10.0, 10, setType = SetType.DROP),
            PlannedSet(5.0, 20, setType = SetType.WARMUP),
            PlannedSet(12.0, 8, completed = true),
        )

        assertEquals(listOf("W", "1", "2", "W", "3"), sets.indices.map(sets::workSetLabel))
        assertEquals(3, sets.workSetCount())
        assertEquals(1, sets.completedWorkSetCount())
    }

    @Test
    fun `removed legacy set types fall back to normal`() {
        assertEquals(SetType.NORMAL, SetType.fromStorage("컴파운드"))
        assertEquals(SetType.NORMAL, SetType.fromStorage("트라이"))
        assertEquals(SetType.NORMAL, SetType.fromStorage("암랩"))
    }

    @Test
    fun `moving one exercise shifts every intervening index as a block`() {
        assertEquals(2, adjustedIndexAfterMove(current = 0, from = 0, to = 2))
        assertEquals(0, adjustedIndexAfterMove(current = 1, from = 0, to = 2))
        assertEquals(1, adjustedIndexAfterMove(current = 2, from = 0, to = 2))

        assertEquals(0, adjustedIndexAfterMove(current = 2, from = 2, to = 0))
        assertEquals(1, adjustedIndexAfterMove(current = 0, from = 2, to = 0))
        assertEquals(2, adjustedIndexAfterMove(current = 1, from = 2, to = 0))
    }
}
