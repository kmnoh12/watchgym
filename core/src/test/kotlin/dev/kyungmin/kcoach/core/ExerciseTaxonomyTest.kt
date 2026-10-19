package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ExerciseTaxonomyTest {
    @Test
    fun `back splits into vertical and horizontal pulls`() {
        assertEquals(0 to "수직 당기기", ExerciseTaxonomy.section("등", "vertical_pull"))
        assertEquals(1 to "수평 당기기", ExerciseTaxonomy.section("등", "row"))
    }

    @Test
    fun `unknown patterns fall into other and sort last`() {
        assertEquals(ExerciseTaxonomy.OTHER_ORDER to "기타", ExerciseTaxonomy.section("등", "unknown_pattern"))
        assertEquals(ExerciseTaxonomy.OTHER_ORDER to "기타", ExerciseTaxonomy.section("등", ""))
        assertEquals(ExerciseTaxonomy.OTHER_ORDER to "기타", ExerciseTaxonomy.section("전체", "row"))
    }

    @Test
    fun `the same pattern means different things per body part`() {
        assertEquals("프레스", ExerciseTaxonomy.section("가슴", "chest_press").second)
        assertEquals("프레스", ExerciseTaxonomy.section("하체", "leg_press").second)
    }

    @Test
    fun `body parts keep the chip order and unknown ones go last`() {
        assertEquals(0, ExerciseTaxonomy.bodyRank("가슴"))
        assertEquals(ExerciseTaxonomy.bodyOrder.size, ExerciseTaxonomy.bodyRank("전체"))
    }
}
