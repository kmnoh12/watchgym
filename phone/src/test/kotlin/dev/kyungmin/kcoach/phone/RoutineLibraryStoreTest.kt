package dev.kyungmin.kcoach.phone

import java.io.File
import java.io.IOException
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoutineLibraryStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun file() = File(temp.root, "library.json")
    private fun custom(id: String = "custom_1720000000000") =
        ExerciseDefinition(id, "같은 이름", "사용자 정의", "전체", "커스텀", true, "curl", "가족")
    private fun library(): RoutineLibrary {
        val exercise = custom()
        val sets = mutableListOf(PlannedSet(12.5, 13, setType = SetType.DROP, gripType = "뉴트럴",
            rpe = "8.5", restSeconds = 45, setId = "original-set", attemptId = "original-attempt"))
        return RoutineLibrary(listOf(WorkoutRoutine("custom_old_routine", "내 루틴", "이두", "오늘",
            mutableListOf(RoutineExercise(exercise, sets, 120)), LocalDate.of(2026, 10, 15).toEpochDay())),
            listOf(exercise, custom("another-original-id")))
    }

    @Test fun restartRestoresOrderDefinitionsAndEveryPlannedFieldWithoutChangingIds() {
        val store = RoutineLibraryStore(file())
        assertNull(store.load().getOrThrow())
        val original = library()
        store.save(original).getOrThrow()
        assertEquals(original, RoutineLibraryStore(file()).load().getOrThrow())
    }

    @Test fun defaultsKeepAllCatalogAndRoutineIdsAfterRestart() {
        val original = RoutineLibrary(WorkoutData.defaultRoutines(), emptyList())
        val store = RoutineLibraryStore(file())
        store.load().getOrThrow()
        store.save(original).getOrThrow()
        assertEquals(original, RoutineLibraryStore(file()).load().getOrThrow())
        assertTrue(original.routines.all { it.lastPerformed == "기록 없음" })
    }

    @Test fun legacyRestMigrationChangesOnlyNinetyAndCorrectsWeightedDipDefinition() {
        val store = RoutineLibraryStore(file())
        store.load().getOrThrow()
        val items = listOf("chest_press", "curl", "").flatMap { pattern ->
            listOf(0, 45, 89, 90, 91, 150).map { rest ->
                RoutineExercise(custom().copy(pattern = pattern), mutableListOf(PlannedSet(27.0, 10, restSeconds = 90)), rest)
            }
        } + RoutineExercise(WorkoutData.catalog.single { it.id == "weighted_dip" }.copy(equipment = "맨몸"), mutableListOf(PlannedSet(5.0, 10)), 90)
        val routine = library().routines.single().copy(exercises = items.toMutableList())
        store.save(RoutineLibrary(listOf(routine), emptyList())).getOrThrow()
        val migrated = RoutineLibraryStore(file()).load().getOrThrow()!!
        val restored = migrated.routines.single().exercises
        items.zip(restored).forEach { (before, after) ->
            assertEquals(if (before.restSeconds == 90) ExerciseDefaults.restSeconds(before.exercise) else before.restSeconds, after.restSeconds)
            assertEquals(before.sets, after.sets)
        }
        assertEquals("기타", restored.last().exercise.equipment)
        store.save(migrated).getOrThrow()
        assertEquals(migrated, RoutineLibraryStore(file()).load().getOrThrow())
    }

    @Test fun emptySavedLibraryDoesNotBecomeDefaults() {
        val store = RoutineLibraryStore(file())
        store.load().getOrThrow()
        store.save(RoutineLibrary(emptyList(), emptyList())).getOrThrow()
        assertEquals(RoutineLibrary(emptyList(), emptyList()), RoutineLibraryStore(file()).load().getOrThrow())
    }

    @Test fun partialWriteFailurePreservesPreviousFileAndAllowsExplicitRetry() {
        val normal = RoutineLibraryStore(file())
        normal.load().getOrThrow()
        val original = library()
        normal.save(original).getOrThrow()
        val failing = RoutineLibraryStore(file()) { stream, bytes ->
            stream.write(bytes, 0, bytes.size / 2)
            throw IOException("disk failure")
        }
        failing.load().getOrThrow()
        assertTrue(failing.save(RoutineLibrary(emptyList(), emptyList())).isFailure)
        assertEquals(original, RoutineLibraryStore(file()).load().getOrThrow())
        normal.save(RoutineLibrary(emptyList(), emptyList())).getOrThrow()
        assertEquals(emptyList<WorkoutRoutine>(), normal.load().getOrThrow()!!.routines)
    }

    @Test fun unreadableOrUnsupportedDataCannotBeOverwrittenWithDefaults() {
        for (text in listOf("{truncated", "{\"version\":2}", "{\"version\":1,\"routines\":[],\"customExercises\":[{}]}")) {
            file().writeText(text)
            val store = RoutineLibraryStore(file())
            assertTrue(store.load().isFailure)
            assertTrue(store.save(RoutineLibrary(WorkoutData.defaultRoutines(), emptyList())).isFailure)
            assertEquals(text, file().readText())
        }
    }

    @Test fun savingWithoutSuccessfulLoadOrAfterExternalCorruptionCannotReplaceData() {
        val store = RoutineLibraryStore(file())
        assertTrue(store.save(library()).isFailure)
        store.load().getOrThrow()
        store.save(library()).getOrThrow()
        file().writeText("unreadable")
        assertTrue(store.save(RoutineLibrary(emptyList(), emptyList())).isFailure)
        assertEquals("unreadable", file().readText())
    }

    @Test fun invalidPlansDoNotReplaceLastSavedDataAndCompletionIsNotStoredInTemplates() {
        val store = RoutineLibraryStore(file())
        store.load().getOrThrow()
        val original = library()
        store.save(original).getOrThrow()
        original.routines.single().exercises.single().sets.single().weightKg = Double.NaN
        assertTrue(store.save(original).isFailure)
        val restored = store.load().getOrThrow()!!
        assertEquals(12.5, restored.routines.single().exercises.single().sets.single().weightKg!!, 0.0)
        restored.routines.single().exercises.single().sets.single().apply {
            completed = true; performedReps = 9; repSource = dev.kyungmin.kcoach.core.SetRepSource.MANUAL
        }
        store.save(restored).getOrThrow()
        val plan = store.load().getOrThrow()!!.routines.single().exercises.single().sets.single()
        assertFalse(plan.completed)
        assertNull(plan.performedReps)
        assertNull(plan.repSource)
        assertEquals("original-set", plan.setId)
    }

    @Test fun relativeDateMovesWithTodayAndUnknownDefaultsHaveNoInventedHistory() {
        val routine = library().routines.single()
        assertEquals("오늘", routine.lastPerformedLabel(LocalDate.of(2026, 10, 15)))
        assertEquals("1일 전", routine.lastPerformedLabel(LocalDate.of(2026, 10, 16)))
        assertEquals("18일 전", routine.lastPerformedLabel(LocalDate.of(2026, 11, 2)))
        assertEquals("기록 없음", routine.copy(lastPerformedEpochDay = null).lastPerformedLabel(LocalDate.now()))
    }
}
