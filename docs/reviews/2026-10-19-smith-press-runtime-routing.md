# Smith shoulder press runtime routing (2026-10-19)

The user reported extra counts and irregular rep vibration during a real Smith shoulder press set. The screenshot records 24 completed repetitions against a target of 10. The actual performed count of that workout is not established by the screenshot.

## Confirmed configuration defect

The October 17 vertical-press migration registered only `dumbbell_shoulder_press`. `smith_shoulder_press` still used `press-return-family-v4` (combined motion). Both supplied paired-calibration exports also freeze that older profile. Camera pose counting, paired-calibration watch counting, and ordinary workout counting must not be treated as one validation result.

Route new Smith attempts through the existing `vertical-press-return-family-v1`, with unchanged constants and limited-automatic capability. Preserve v3/v4 frozen snapshots for historical replay and restored in-progress attempts. Machine presses and unrelated exercises remain unchanged.

## Detector-only replay evidence

Two private original exports contain 1484 and 1467 sensor samples, respectively. Both older frozen replays count 10; both vertical v1 candidate replays also count 10. The first export has a user-reviewed total of 10. The second has no persisted review total; the user states 10 in chat.

Equal totals hide different event timing. In the first older replay, adjacent events are separated by as much as 5.51 seconds and as little as 1.50 seconds; vertical v1 events are separated by approximately 2.20–2.50 seconds. In the second, older intervals range approximately 1.26–4.42 seconds; vertical v1 approximately 2.14–2.46 seconds. These are event intervals, not human-reviewed repetition boundaries, and do not prove that a particular rep was missed or doubled.

After migration, the second export replays as frozen=10/current=10 with the current timestamps matching vertical v1. No target count, camera signal, frame cutoff, fabricated tail, or new threshold was added to the detector.

## Unresolved physical behavior

The screenshot's ordinary-workout set is not present in these two archives, which are paired-calibration sessions. The 24-count symptom is not reproduced by those archives. Runtime preparation, start-pose state and active personalized pattern selection are outside this detector-only replay.

Vertical v1 can close a return when the negative velocity proxy has recovered halfway from its peak. It does not observe absolute wrist height or establish that the arm returned to the user's initial right-angle position. Exact vibration phase remains unverified. Matching a total of 10 is not acceptance of return timing or full workout accuracy. No device installation or live workout test was performed.

The prior graph cutoff investigation is separate: the full raw sensor/video tail is retained while the review chart uses the camera completion boundary. That display issue is not fixed by this routing change.

## Verification

A focused TrackingProfileSnapshotTest first failed for Smith routing, then passed after migration. Focused snapshot, vertical-detector and selected-export replay tests passed. Existing private-dataset tests skip explicitly when their original datasets are not configured. No full suite or lint was run.

## Return-timing experiment

Both reference videos were preprocessed with `claude-real-video`, transcription skipped, and chronological contact sheets inspected. They show seated unloaded overhead-press motions at home, not the weighted Smith workout shown in the screenshot. Their movement is useful for return-phase investigation; it is not a loaded-workout acceptance test.

An isolated copy of the causal v1 detector changed only the negative-lobe recovery fraction from 0.50 to 0.15. It was compared on the first export, then the unchanged candidate was replayed on the second. Both files retain 10 events. The first candidate's event delay versus v1 is approximately 140–220 ms; the second approximately 114–280 ms. This is a measured change in detector timing, not a measured improvement against manually reviewed return timestamps. Camera wrist-height records confirm motion continues after some existing completion markers, but no exact 20-rep video boundary truth was persisted.

The temporary experiment source and local results remain under ignored `work/press-two-captures`; the candidate is NOT enabled in the runtime. Constants and the v1 algorithm ID are unchanged. A timing revision must receive a new algorithm version and preserve frozen v1 replay before it is enabled. Full return-to-start-position accuracy and weighted-workout overcounting remain unresolved.

## Same 60-set MM-Fit comparison: timing candidate rejected

Replayed the same converted MM-Fit 20 sessions / 60 dumbbell-shoulder-press sets, preserving the original label intervals and sensor samples. The recovery fraction was fixed at 0.15 before this run; no parameter search was performed on the 60 labels. Only `:core:test --tests '*VerticalPressReplayComparisonTest'` ran.

| Detector | MAE (reps/set) | Exact total | Within one rep |
| --- | ---: | ---: | ---: |
| Frozen combined v4 | 4.833 | 4/60 (6.7%) | 8/60 (13.3%) |
| Vertical v1, recovery 0.50 | 0.667 | 25/60 (41.7%) | 55/60 (91.7%) |
| Timing candidate, recovery 0.15 | 0.867 | 13/60 (21.7%) | 55/60 (91.7%) |

Relative to vertical v1, the timing candidate improves 0 sets, leaves 48 unchanged and worsens 12. Every changed set loses one counted rep: w04 sets 1/2/3; w07 sets 1/3; w11 set 1; w12 sets 2/3; w15 set 3; w17 set 3; w18 set 2; w19 set 2 (set numbering within each session's shoulder-press labels). Event closure near a label boundary needs separate investigation; no original interval was extended and no fabricated terminal samples were added to rescue a final rep.

The unchanged 91.7% tolerance metric hides a reduction in exact totals and a worse MAE. Reject the 0.15 candidate for runtime adoption. The existing Smith-to-v1 routing fix is separate and remains unchanged. The comparison fixture is test-only and the result CSV includes every set. This is a previously explored benchmark, not an independent holdout or real Watch8 validation, and its count labels do not establish vibration timing at the physical return pose.

## Boundary and state-machine diagnosis

A focused diagnostic preserves the strict benchmark score and separately reads genuine pre/post samples. No fabricated samples or synthetic end-of-set completion are used.

Strict vertical v1 errors are 25 exact, 30 undercounts by one, 5 undercounts by two, and no overcounts. The 0.15 candidate has 13 exact, 42 undercounts by one and 5 undercounts by two. In the v1 diagnostic, 24 sets emit a cycle that started within the label but ends in the observed next two seconds. Those tail events are not added to the strict score; they are not manually verified physical returns. Counting them diagnostically would give 44/60 exact, which is not an improved detector accuracy claim.

All 12 sets that lose an additional event with the 0.15 candidate have a pending final cycle at the label end. Seven close in the real next two seconds. Of the other five, w04 sets 1/2 and w07 set 3 hit the existing 3000 ms lobe duration limit and discard an ascent-backed negative lobe (3002, 3005 and 3011 ms respectively). w12 sets 2/3 do not close within the observed two-second diagnostic horizon. Raw tail data exists for all five; absence of a closure is not absence of data.

Prefeeding two seconds of arbitrary recorded context and then resetting counting worsens strict totals substantially (v1 6/60 exact; candidate 3/60); this must not be adopted as a blanket initialization fix. It is not a verified stationary-preparation sequence.

The next engine experiment must couple return-phase state, bounded final-cycle grace, setup-motion rejection and consistent timeout semantics. Relaxing the recovery fraction alone is rejected. Increasing a timeout alone is also not established as correct because rack/put-down motion can contaminate completion. Keep count matching, return-phase timing and false positives as separate evaluation criteria. MM-Fit label boundaries need to be understood independently before claiming a physical return was missed; delayed events beyond labels must not silently inflate strict metrics. The screenshot's 24-count real workout remains separate evidence not supplied as a raw session in the two paired exports.

Reproduction: run only `:core:test --tests '*PressBoundaryDiagnosticTest'` with the existing `KCOACH_MMFIT_DIR`. CSVs distinguish strict counts, observed-tail closures, pending state and closed-lobe reasons. This investigation makes no production detector or timeout change.
