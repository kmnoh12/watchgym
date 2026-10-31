package dev.kyungmin.kcoach.core

import kotlin.test.*

class ProgressionTargetsTest {
    private val engine = ProgressionEngine()
    private fun plan(vararg sets: Pair<Double?, Int>, pattern: String = "chest_press", upper: Int? = null, warmup: Boolean = false, load: ProgressionLoad = ProgressionLoad.EXTERNAL) =
        ProgressionPlan(ProgressionKey("a", "exercise", 0, "바벨"), sets.mapIndexed { i, s -> ProgressionSetTarget("s$i", s.first, s.second, warmup && i == 0, upper) }, pattern, load)
    private fun log(plan: ProgressionPlan, day: Long = 1, reps: List<Int> = plan.sets.map { it.targetReps }) =
        ExerciseLog(plan.key.exerciseId, day, plan.sets.mapIndexed { i, s -> LoggedSet(s.weightKg, reps[i], s.targetReps, s.isWarmup, positionKey = s.positionKey, upperReps = s.upperReps) },
            "session$day", day * 1000, plan.key.routineId, plan.key.exerciseIndex, plan.key.equipment)
    private fun next(plan: ProgressionPlan, vararg logs: ExerciseLog, speed: ProgressionSpeed = ProgressionSpeed.NORMAL, increment: IncrementCandidate = IncrementCandidate(5.0, false)) =
        engine.nextTargets(plan, logs.toList(), ProgressionSettings(speed = speed), increment)

    @Test fun benchIllustrationWithExplicitFiveToEightRange() {
        var p = plan(80.0 to 5, upper = 8).let { it.copy(sets = it.sets.map { set -> set.copy(lowerReps = 5) }) }
        for (r in 6..8) {
            p = p.copy(sets = next(p, log(p))!!)
            assertEquals(r, p.sets.single().targetReps)
        }
        val first = log(p, 1)
        assertEquals(p.sets, next(p, first))
        val raised = next(p, first, log(p, 2))!!.single()
        // Explicit example 5–8 has width 3; production defaults remain 6–10.
        assertEquals(85.0, raised.weightKg)
        assertEquals(5, raised.targetReps)
    }
    @Test fun defaultCompoundUsesSixToTen() {
        val p = plan(80.0 to 10)
        val result = next(p, log(p, 1), log(p, 2))!!.single()
        assertEquals(85.0, result.weightKg)
        assertEquals(6, result.targetReps)
    }
    @Test fun curlTwelveToSix() {
        val p = plan(20.0 to 12, pattern = "curl")
        val result = next(p, log(p, 1), log(p, 2))!!.single()
        assertEquals(25.0, result.weightKg)
        assertEquals(6, result.targetReps)
    }
    @Test fun pyramidOnlyRaisesTheHeaviestGroupAndPreservesWarmup() {
        val p = plan(20.0 to 15, 60.0 to 12, 60.0 to 8, 70.0 to 10, 70.0 to 10, warmup = true)
        assertEquals(p.sets.map { it.weightKg }, next(p, log(p))!!.map { it.weightKg })
        val result = next(p, log(p, 1), log(p, 2))!!
        assertEquals(listOf(20.0, 60.0, 60.0, 75.0, 75.0), result.map { it.weightKg })
        assertEquals(listOf(15, 12, 8, 6, 6), result.map { it.targetReps })
    }
    @Test fun onlyFirstBelowUpperGainsOneRep() {
        var p = plan(70.0 to 8, 70.0 to 4, 70.0 to 10)
        p = p.copy(sets = next(p, log(p))!!)
        assertEquals(listOf(9, 4, 10), p.sets.map { it.targetReps })
        p = p.copy(sets = next(p, log(p))!!)
        assertEquals(listOf(10, 4, 10), p.sets.map { it.targetReps })
    }
    @Test fun changingBackoffSetsPreservesMainStreak() {
        val old = plan(70.0 to 10, 70.0 to 10, 60.0 to 8)
        val current = plan(70.0 to 10, 70.0 to 10, 50.0 to 7)
        val result = next(current, log(old, 1), log(current, 2))!!
        assertEquals(listOf(75.0, 75.0, 50.0), result.map { it.weightKg })
        assertEquals(listOf(6, 6, 7), result.map { it.targetReps })
    }
    @Test fun singleRemainingMainSetUsesAllHistoricalMainSets() {
        val old = plan(70.0 to 10, 70.0 to 10)
        val current = plan(70.0 to 10, 60.0 to 8)
        val result = next(current, log(old, 1), log(current, 2))!!
        assertEquals(listOf(75.0, 60.0), result.map { it.weightKg })
        assertEquals(listOf(6, 8), result.map { it.targetReps })
        val failed = log(old, 1, listOf(10, 9))
        assertEquals(listOf(70.0, 60.0), next(current, failed, log(current, 2))!!.map { it.weightKg })
    }
    @Test fun differentHistoricalMainWeightBreaksStreakEvenWhenCurrentWeightWasPresent() {
        val current = plan(70.0 to 10, 60.0 to 10)
        val differentMain = plan(70.0 to 10, 75.0 to 10)
        assertEquals(listOf(70.0, 60.0), next(current, log(current, 1), log(differentMain, 2), log(current, 3))!!.map { it.weightKg })
    }
    @Test fun assistanceMainIsLightestAndExcludesWarmup() {
        val old = plan(5.0 to 10, 20.0 to 10, 20.0 to 10, warmup = true, load = ProgressionLoad.ASSISTANCE)
        val current = plan(5.0 to 10, 20.0 to 10, 30.0 to 8, warmup = true, load = ProgressionLoad.ASSISTANCE)
        assertEquals(listOf(5.0, 15.0, 30.0), next(current, log(old, 1), log(current, 2))!!.map { it.weightKg })
        val differentMain = old.copy(sets = old.sets.mapIndexed { i, s -> if (i == 2) s.copy(weightKg = 15.0) else s })
        assertEquals(listOf(5.0, 20.0, 30.0), next(current, log(current, 1), log(differentMain, 2), log(current, 3))!!.map { it.weightKg })
    }
    @Test fun incompleteGroupDoesNotGainReps() {
        val p = plan(70.0 to 8, 70.0 to 4, 70.0 to 10)
        assertEquals(p.sets, next(p, log(p, reps = listOf(8, 3, 10)))!!.map { it.copy(upperReps = null) })
    }
    @Test fun heavierMissAllowsSuccessfulBackoffGroup() {
        val p = plan(60.0 to 8, 70.0 to 8)
        assertEquals(listOf(9, 8), next(p, log(p, reps = listOf(8, 7)))!!.map { it.targetReps })
    }
    @Test fun missingRepsDoNotResetStreakOrCountAsSuccess() {
        val p = plan(70.0 to 10)
        val missing = log(p, 2).copy(sets = log(p).sets.map { it.copy(reps = 0, repsEntered = false, completed = false) })
        assertEquals(75.0, next(p, log(p, 1), missing, log(p, 3))!!.single().weightKg)
        assertEquals(70.0, next(p, missing, log(p, 3))!!.single().weightKg)
    }
    @Test fun confirmedCorrectionBreaksTheStreak() {
        val p = plan(70.0 to 10)
        assertEquals(70.0, next(p, log(p, 1), log(p, 2, listOf(9)), log(p, 3))!!.single().weightKg)
    }
    @Test fun speedOnlyChangesConfirmations() {
        val p = plan(70.0 to 10)
        assertEquals(75.0, next(p, log(p), speed = ProgressionSpeed.FAST)!!.single().weightKg)
        assertEquals(70.0, next(p, log(p, 1), log(p, 2), speed = ProgressionSpeed.SLOW)!!.single().weightKg)
        assertEquals(75.0, next(p, log(p, 1), log(p, 2), log(p, 3), speed = ProgressionSpeed.SLOW)!!.single().weightKg)
    }
    @Test fun layoutChangesAndDifferentContextsHaveNoInheritedHistory() {
        val p = plan(70.0 to 10, 70.0 to 10)
        val old = log(p)
        assertNull(next(p.copy(sets = p.sets.reversed()), old))
        assertNull(next(p.copy(sets = p.sets + p.sets[0].copy(positionKey = "new")), old))
        assertNull(next(p.copy(key = p.key.copy(routineId = "b")), old))
        assertNull(next(p.copy(key = p.key.copy(exerciseIndex = 1)), old))
        assertNull(next(p.copy(key = p.key.copy(equipment = "덤벨")), old))
        val changed = log(p, 2).copy(sets = old.sets.reversed())
        assertEquals(70.0, next(p, old, changed, log(p, 3))!!.first().weightKg)
    }
    @Test fun firstDayDisabledAndTimeHaveNoSuggestion() {
        val p = plan(70.0 to 8)
        assertNull(next(p))
        assertNull(engine.nextTargets(p, listOf(log(p)), ProgressionSettings(false)))
        assertNull(next(p.copy(load = ProgressionLoad.TIME), log(p)))
    }
    @Test fun sameInputsAreIdempotentAndHigherRoutineGoalDoesNotMoveWithAutomaticReps() {
        val p = plan(70.0 to 14, upper = 14)
        val history = arrayOf(log(p, 1), log(p, 2))
        assertEquals(next(p, *history), next(p, *history))
        assertEquals(10, next(p, *history)!!.single().targetReps)
        val lower = plan(70.0 to 4)
        assertEquals(5, next(lower, log(lower))!!.single().targetReps)
    }
    @Test fun bodyweightAndAssistanceUseTheCorrectDirection() {
        val bw = plan(null to 8, load = ProgressionLoad.BODYWEIGHT)
        assertEquals(9, next(bw, log(bw))!!.single().targetReps)
        val assisted = plan(20.0 to 10, load = ProgressionLoad.ASSISTANCE)
        assertEquals(15.0, next(assisted, log(assisted), speed = ProgressionSpeed.FAST)!!.single().weightKg)
    }
    @Test fun explicitConfirmationStillBlocksAndNoRatioLimitApplies() {
        val p = plan(20.0 to 12, pattern = "curl")
        assertEquals(20.0, next(p, log(p), speed = ProgressionSpeed.FAST, increment = IncrementCandidate(10.0, true))!!.single().weightKg)
        assertEquals(30.0, next(p, log(p), speed = ProgressionSpeed.FAST, increment = IncrementCandidate(10.0, false))!!.single().weightKg)
    }
    @Test fun machineDefaultsUseFiveOrObservedStepsAndConfirmedValueWins() {
        for (equipment in listOf("머신", "케이블", "핀 머신")) {
            val p = plan(20.0 to 10).let { it.copy(key = it.key.copy(equipment = equipment)) }
            for (weights in listOf(emptyList(), listOf(20.0, 20.0))) {
                val increment = ProgressionDefaults.increment(equipment, weights)
                assertEquals(IncrementCandidate(5.0, false), increment)
                assertEquals(25.0, next(p, log(p, 1), log(p, 2), increment = increment)!!.single().weightKg)
            }
            val observed = ProgressionDefaults.increment(equipment, listOf(40.0, 20.0, 30.0, 32.5, 30.0))
            assertEquals(IncrementCandidate(2.5, false, listOf(20.0, 30.0, 32.5, 40.0)), observed)
            assertEquals(30.0, next(p, log(p, 1), log(p, 2), increment = observed)!!.single().weightKg)
            val confirmed = ProgressionDefaults.increment(equipment, listOf(20.0, 30.0), confirmedKg = 1.0)
            assertEquals(IncrementCandidate(1.0, false, confirmed = true), confirmed)
            assertEquals(21.0, next(p, log(p, 1), log(p, 2), increment = confirmed)!!.single().weightKg)
        }
        assertEquals(IncrementCandidate(null, true), ProgressionDefaults.increment("unknown", listOf(20.0, 30.0)))
    }
    @Test fun irregularMachineStagesUseNearestNextInBothDirectionsAndHoldAtUnknownEnds() {
        val increment = ProgressionDefaults.increment("머신", listOf(54.0, 27.0, 35.0, 45.0, 35.0))
        assertEquals(35.0, ProgressionDefaults.nextWeight(27.0, increment, false))
        assertEquals(45.0, ProgressionDefaults.nextWeight(35.0, increment, false))
        assertEquals(54.0, ProgressionDefaults.nextWeight(45.0, increment, false))
        assertEquals(54.0, ProgressionDefaults.nextWeight(54.0, increment, false))
        assertEquals(35.0, ProgressionDefaults.nextWeight(45.0, increment, true))
        assertEquals(27.0, ProgressionDefaults.nextWeight(27.0, increment, true))
        val p = plan(35.0 to 10).let { it.copy(key = it.key.copy(equipment = "머신")) }
        assertEquals(45.0, next(p, log(p, 1), log(p, 2), increment = increment)!!.single().weightKg)
        assertEquals(36.0, ProgressionDefaults.nextWeight(35.0,
            ProgressionDefaults.increment("머신", listOf(27.0, 35.0, 45.0, 54.0), 1.0), false))
        assertEquals(37.5, ProgressionDefaults.nextWeight(35.5,
            ProgressionDefaults.increment("덤벨", confirmedKg = 2.0), false))
    }
    @Test fun generatedWeightsSnapAndUniformAndConfirmedStepsRemainSupported() {
        assertEquals(65.0, ProgressionDefaults.roundWeight(63.2, "머신"))
        assertEquals(64.0, ProgressionDefaults.roundWeight(63.2, "덤벨"))
        assertEquals(IncrementCandidate(2.0, false), ProgressionDefaults.increment("바벨"))
        assertEquals(IncrementCandidate(10.0, false), ProgressionDefaults.increment("머신", listOf(20.0, 30.0, 40.0)))
        assertEquals(65.0, ProgressionDefaults.nextWeight(58.2, IncrementCandidate(5.0, false), false))
        assertEquals(0.0, ProgressionDefaults.nextWeight(2.0, IncrementCandidate(5.0, false), true))
        assertEquals(IncrementCandidate(5.0, false), ProgressionDefaults.increment("머신", listOf(Double.NaN, -1.0)))
    }
    @Test fun legacyAndExtendedCodecRoundTrip() {
        assertEquals(LoggedSet(60.0, 10), ExerciseHistoryCodec.decode("bench\t1\t60:10").single().sets.single())
        assertEquals(LoggedSet(null, 8), ExerciseHistoryCodec.decode("pullup\t1\t-:8\ts1\t1").single().sets.single())
        val p = plan(20.0 to 15, 70.0 to 10, warmup = true)
        val logs = listOf(log(p))
        assertEquals(logs, ExerciseHistoryCodec.decode(ExerciseHistoryCodec.encode(logs)))
    }
    @Test fun lowConfidenceHoldsWithoutBecomingAZeroRepMiss() {
        val p = plan(70.0 to 10)
        val low = log(p, 2).let { it.copy(sets = it.sets.map { set -> set.copy(progressionEligible = false) }) }
        assertEquals(70.0, next(p, log(p, 1), low)!!.single().weightKg)
        assertEquals(75.0, next(p, log(p, 1), low, log(p, 3))!!.single().weightKg)
    }
    @Test fun completedButMissingRepsAndNewWeightCannotInheritSuccesses() {
        val p = plan(70.0 to 10)
        val missing = log(p, 2).let { it.copy(sets = it.sets.map { set -> set.copy(reps = 0, repsEntered = false) }) }
        assertEquals(75.0, next(p, log(p, 1), missing, log(p, 3))!!.single().weightKg)
        val raised = p.copy(sets = p.sets.map { it.copy(weightKg = 75.0) })
        assertEquals(75.0, next(raised, log(p, 1), log(raised, 2))!!.single().weightKg)
    }
    @Test fun estimatesOnlyUseTwoToTen() {
        assertNull(EstimatedOneRepMax.epley(70.0, 1))
        assertNull(EstimatedOneRepMax.epley(70.0, 11))
        assertEquals(40.0, EstimatedOneRepMax.epley(30.0, 10))
    }
}
