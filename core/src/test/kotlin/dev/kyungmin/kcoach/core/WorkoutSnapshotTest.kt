package dev.kyungmin.kcoach.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class WorkoutSnapshotTest {
    private val snapshot = WorkoutSnapshot(
        sessionId = "phone-1",
        routineId = "shoulders",
        exerciseIndex = 2,
        setIndex = 1,
        plannedSets = listOf(
            PlannedSetSnapshot(0, "external_rotation", 0, 25, 5.0, true, "WARMUP", "외회전", 45, 90, 45),
            PlannedSetSnapshot(0, "external_rotation", 1, 25, null, false, performedReps = 0, repSource = SetRepSource.PHONE_ANALYSIS),
        ),
        performedReps = mapOf("external_rotation" to 25),
        phase = WorkoutPhase.RESTING,
        restEndAtMs = 50_000L,
        pausedRestRemainingMs = 0L,
        paused = false,
        startedAtMs = 1_000L,
        pausedSinceMs = 0L,
        totalPausedMs = 500L,
        lastSequence = 7L,
        lastCompletedSetKey = "phone-1:external_rotation:0",
        captureStates = mapOf("capture-1" to CaptureState.ANALYZING),
        routineName = "내 운동",
        restRev = 42, watchRestSeq = 17,
    )

    @Test
    fun `snapshot round trips every recovery field`() {
        assertEquals(snapshot, WorkoutSnapshotCodec.decode(WorkoutSnapshotCodec.encode(snapshot)))
    }

    /** Writes the layout that schema 6 used: no per-set one-arm state and no rest versions. */
    private fun versionSixBytes(value: WorkoutSnapshot): ByteArray {
        val output = ByteArrayOutputStream()
        fun DataOutputStream.str(text: String) { val b = text.toByteArray(Charsets.UTF_8); writeInt(b.size); write(b) }
        DataOutputStream(output).use { data ->
            data.writeInt(0x4B435331); data.writeInt(6)
            data.str(value.sessionId); data.str(value.routineId)
            data.writeInt(value.exerciseIndex); data.writeInt(value.setIndex)
            data.writeInt(value.plannedSets.size)
            value.plannedSets.forEach { set ->
                data.writeInt(set.exerciseIndex); data.str(set.exerciseId); data.writeInt(set.setIndex); data.writeInt(set.targetReps)
                data.writeBoolean(set.weightKg != null); set.weightKg?.let(data::writeDouble)
                data.writeBoolean(set.completed); data.str(set.setType); data.str(set.exerciseName)
                data.writeInt(set.restSeconds); data.writeInt(set.exerciseRestSeconds)
                data.writeBoolean(set.restOverrideSeconds != null); set.restOverrideSeconds?.let(data::writeInt)
                data.writeBoolean(set.performedReps != null); set.performedReps?.let(data::writeInt)
                data.str(set.repSource?.name.orEmpty())
                data.str(set.exerciseMuscles); data.str(set.bodyCategory); data.str(set.equipment)
                data.str(set.pattern); data.str(set.family); data.str(set.gripType)
                data.writeBoolean(set.rpe != null); set.rpe?.let { data.str(it) }
                data.str(set.setId); data.str(set.attemptId); data.writeBoolean(set.identityRequired)
            }
            data.writeInt(value.performedReps.size)
            value.performedReps.toSortedMap().forEach { (id, reps) -> data.str(id); data.writeInt(reps) }
            data.writeInt(value.phase.ordinal); data.writeLong(value.restEndAtMs); data.writeLong(value.pausedRestRemainingMs)
            data.writeBoolean(value.paused); data.writeLong(value.startedAtMs); data.writeLong(value.pausedSinceMs)
            data.writeLong(value.totalPausedMs); data.writeLong(value.lastSequence)
            data.writeBoolean(value.lastCompletedSetKey != null); value.lastCompletedSetKey?.let { data.str(it) }
            data.writeInt(value.captureStates.size)
            value.captureStates.toSortedMap().forEach { (id, state) -> data.str(id); data.writeInt(state.ordinal) }
            data.str(value.routineName)
        }
        return output.toByteArray()
    }

    @Test
    fun `version six snapshots restore missing rest versions as zero`() {
        val decoded = WorkoutSnapshotCodec.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(versionSixBytes(snapshot)))
        assertEquals(snapshot.copy(restRev = 0, watchRestSeq = 0), decoded)
    }

    @Test
    fun `snapshot persists the planned set type`() {
        val decoded = WorkoutSnapshotCodec.decode(WorkoutSnapshotCodec.encode(snapshot))
        assertEquals("WARMUP", decoded.plannedSets.first().setType)
        assertEquals("외회전", decoded.plannedSets.first().exerciseName)
        assertEquals(45, decoded.plannedSets.first().restSeconds)
        assertEquals(90, decoded.plannedSets.first().exerciseRestSeconds)
        assertEquals(45, decoded.plannedSets.first().restOverrideSeconds)
    }

    @Test
    fun `version one snapshots migrate with normal set types`() {
        val decoded = WorkoutSnapshotCodec.decode(legacySnapshot(1))
        assertEquals(WorkoutSnapshot.CURRENT_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(listOf("NORMAL", "NORMAL"), decoded.plannedSets.map { it.setType })
        assertEquals(0L, decoded.restRev); assertEquals(0L, decoded.watchRestSeq)
    }

    @Test
    fun `version two snapshots preserve types and default missing rest metadata`() {
        val decoded = WorkoutSnapshotCodec.decode(legacySnapshot(2))
        assertEquals("WARMUP", decoded.plannedSets.first().setType)
        assertEquals("", decoded.plannedSets.first().exerciseName)
        assertEquals(90, decoded.plannedSets.first().restSeconds)
    }

    @Test
    fun `version three snapshots never invent per-set counts`() {
        val restored = WorkoutSnapshotCodec.decode(legacySnapshot(3))
        assertEquals(null, restored.plannedSets.first().performedReps)
        assertEquals(null, restored.plannedSets.first().repSource)
        assertEquals(45, restored.plannedSets.first().restSeconds)
    }

    @Test
    fun `version four counts survive without inventing custom metadata`() {
        val restored = WorkoutSnapshotCodec.decode(legacySnapshot(4))
        assertEquals(0, restored.plannedSets[1].performedReps)
        assertEquals(SetRepSource.PHONE_ANALYSIS, restored.plannedSets[1].repSource)
        assertEquals("", restored.routineName)
        assertEquals("", restored.plannedSets.first().equipment)
    }


    @Test fun `version five preserves custom metadata without inventing stable identities`() {
        val restored = WorkoutSnapshotCodec.decode(legacySnapshot(5))
        assertEquals(snapshot.routineName, restored.routineName)
        assertEquals(snapshot.plannedSets[1].performedReps, restored.plannedSets[1].performedReps)
        assertEquals("", restored.plannedSets.first().setId)
        assertEquals("", restored.plannedSets.first().attemptId)
        assertEquals(false, restored.plannedSets.first().identityRequired)
        assertEquals(snapshot.plannedSets.first().gripType, restored.plannedSets.first().gripType)
    }

    @Test fun `set identities round trip and cannot be duplicated`() {
        val identified = snapshot.copy(plannedSets = snapshot.plannedSets.mapIndexed { index, set ->
            set.copy(setId = "set-$index", attemptId = "attempt-$index", identityRequired = true)
        })
        assertEquals(identified, WorkoutSnapshotCodec.decode(WorkoutSnapshotCodec.encode(identified)))
        assertFails { identified.copy(plannedSets = identified.plannedSets.map { it.copy(setId = "duplicate") }) }
    }

    @Test
    fun `truncated snapshot is rejected`() {
        val encoded = WorkoutSnapshotCodec.encode(snapshot)
        assertFails { WorkoutSnapshotCodec.decode(encoded.dropLast(8)) }
    }

    @Test
    fun `invalid runtime values are rejected before persistence`() {
        assertFails { snapshot.copy(performedReps = mapOf("external_rotation" to -1)) }
        assertFails { snapshot.copy(restEndAtMs = -1L) }
        assertFails {
            snapshot.copy(
                plannedSets = listOf(PlannedSetSnapshot(0, "external_rotation", 0, 10, Double.NaN, false)),
            )
        }
    }

    private fun legacySnapshot(version: Int): String {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            fun writeString(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                data.writeInt(bytes.size)
                data.write(bytes)
            }
            data.writeInt(0x4B435331)
            data.writeInt(version)
            writeString(snapshot.sessionId)
            writeString(snapshot.routineId)
            data.writeInt(snapshot.exerciseIndex)
            data.writeInt(snapshot.setIndex)
            data.writeInt(snapshot.plannedSets.size)
            snapshot.plannedSets.forEach { set ->
                data.writeInt(set.exerciseIndex)
                writeString(set.exerciseId)
                data.writeInt(set.setIndex)
                data.writeInt(set.targetReps)
                data.writeBoolean(set.weightKg != null)
                set.weightKg?.let(data::writeDouble)
                data.writeBoolean(set.completed)
                if (version >= 2) writeString(set.setType)
                if (version >= 3) {
                    writeString(set.exerciseName); data.writeInt(set.restSeconds); data.writeInt(set.exerciseRestSeconds)
                    data.writeBoolean(set.restOverrideSeconds != null); set.restOverrideSeconds?.let(data::writeInt)
                }
                if (version >= 4) {
                    data.writeBoolean(set.performedReps != null); set.performedReps?.let(data::writeInt)
                    writeString(set.repSource?.name.orEmpty())
                }
                if (version >= 5) {
                    writeString(set.exerciseMuscles); writeString(set.bodyCategory); writeString(set.equipment)
                    writeString(set.pattern); writeString(set.family); writeString(set.gripType)
                    data.writeBoolean(set.rpe != null); set.rpe?.let(::writeString)
                }
            }
            data.writeInt(snapshot.performedReps.size)
            snapshot.performedReps.forEach { (exercise, reps) ->
                writeString(exercise)
                data.writeInt(reps)
            }
            data.writeInt(snapshot.phase.ordinal)
            data.writeLong(snapshot.restEndAtMs)
            data.writeLong(snapshot.pausedRestRemainingMs)
            data.writeBoolean(snapshot.paused)
            data.writeLong(snapshot.startedAtMs)
            data.writeLong(snapshot.pausedSinceMs)
            data.writeLong(snapshot.totalPausedMs)
            data.writeLong(snapshot.lastSequence)
            data.writeBoolean(snapshot.lastCompletedSetKey != null)
            snapshot.lastCompletedSetKey?.let(::writeString)
            data.writeInt(snapshot.captureStates.size)
            snapshot.captureStates.forEach { (captureId, state) ->
                writeString(captureId)
                data.writeInt(state.ordinal)
            }
            if (version >= 5) writeString(snapshot.routineName)
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
    }
}
