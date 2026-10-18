# MM-Fit paper-description reproduction (2026-10-19)

The user asked why custom detection was pursued before reproducing the published method. A paper baseline should have preceded custom-engine claims. Existing PaperPeakRepAnalyzer is adapted from Prabhu et al. (2020), not the MM-Fit section 4.4 method.

Sources: https://vradu.uk/UbiComp2021.pdf (4.4, 5.2, 5.3, Table 7); https://github.com/KDMStromback/mm-fit (master tree inspected; no repetition-counter implementation identified).

Implemented description: three-channel standardization; cubic SG smoothing; first PCA projection; amplitude-ranked peak spacing; local period from centered-window autocorrelation; lower-peak suppression within 0.75 periods; half-of-40th-percentile peak-amplitude cutoff. Target count/video never enters the algorithm.

This is a DESCRIPTION-BASED OFFLINE REPRODUCTION, not author code or an exact reproduction of 0.18 MAE. Missing published settings are declared: SG window 0.5 seconds, repetition bounds 0.6–8 seconds, autocorrelation window +/-8 seconds. PCA sign uses largest absolute loading positive; reverse sign and gyro are also reported, never selected per set from labels. Final preprocessing selects actual source samples near the stated 50 Hz rate without interpolation. It preserves the same 60 label intervals and latest-preceding-acceleration adapter. Exact scoring conditions are not established as identical to the paper.

Existing vertical v1: 25/60 exact, 55/60 within one, MAE 0.667. Final acc/fixed-positive paper-description baseline: 34/60 exact (56.7%), 56/60 within one (93.3%), MAE 0.500. Initial source-rate (~100 Hz) run was 33/60 exact, 56/60 within one, MAE 0.517; the 50 Hz correction follows the paper preprocessing, not a parameter search. All modality/polarity results follow.

{
  "acc_positive": {
    "sets": 60,
    "exact": 34,
    "within_one": 56,
    "mae": 0.5
  },
  "acc_negative": {
    "sets": 60,
    "exact": 28,
    "within_one": 52,
    "mae": 0.6666666666666666
  },
  "gyro_positive": {
    "sets": 60,
    "exact": 16,
    "within_one": 35,
    "mae": 1.4833333333333334
  },
  "gyro_negative": {
    "sets": 60,
    "exact": 20,
    "within_one": 40,
    "mae": 1.3
  }
}

On the two full private ZIPs, acceleration/fixed-positive predicts 10 then 11; the user reports 10 in both. Reverse sign predicts 12 then 10. No per-file sign selection is allowed. No physical return-time accuracy is established. Per-set public-data predictions are in 2026-10-19-mmfit-paper-sets.csv.

Whole-set standardization, PCA, amplitude ranking, centered-window correlation and percentile filtering use future observations. This method can support post-set analysis but is not directly an irreversible live vibration counter. A bounded-delay causal adaptation requires its own validation and version. No runtime detector, release or haptics were changed.

Three focused tests passed: cubic smoothing including boundaries; known ten-period motion at 50/100 Hz with large-epoch invariance; stationary data yields zero. Benchmark completed on 60 sets plus both private ZIPs. These are previously explored development data, not independent Watch8 acceptance. No full suite/build/lint ran.

Reproduce using a numpy-capable Python: tools/test_mmfit_paper_replay.py and tools/mmfit_paper_replay.py --dataset <mmfit-probe> --evidence <private zip> --output <work directory>.
