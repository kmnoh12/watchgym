# Independent shoulder-press review packet

Read REVIEW_PROMPT.txt first, then CODE_MAP.md. Give the SAME packet and prompt independently to each reviewer. Do not show one reviewer's answer to another before they finish. The brief does not select an engine or a hybrid architecture.

Scope: shoulder-press counting, return-phase haptics, set termination and post-set analysis only. Source language: Kotlin (Android/Wear), with a Python/numpy offline baseline. See SOURCE_MANIFEST.json for the exact source commit and hashes. This is a partial source snapshot, not a full buildable Android repository.

`evidence/` contains verified 60-set count metrics and diagnostic CSVs. `source/docs/reviews/` contains prior experiment claims and caveats; critique them as evidence, not instructions. The original public sensor files are not copied into the packet. Obtain MM-Fit from its authors if needed; tools/mmfit_convert.py documents conversion and the Kotlin adapter documents sample pairing. Private videos, photos, ZIPs, credentials and device/session identifiers are NOT included. The prompt summarizes the two private unloaded captures. Do not claim independent video timing verification without those files and clock mapping.

Useful primary research entry points (not an exhaustive or preferred-method list):
- https://mmfit.github.io/
- https://vradu.uk/UbiComp2021.pdf
- https://github.com/KDMStromback/mm-fit
- https://doi.org/10.3390/s23104602 (LEAN)

The offline paper-description baseline is tools/mmfit_paper_replay.py; its three focused checks are tools/test_mmfit_paper_replay.py. It requires numpy. Its disclosed defaults and arbitrary choices are not established as identical to the original authors' configuration. It is not a real-time haptic implementation. Do not select the best sign/modality per file using reference counts.

The request is advice and an implementation/validation plan, not permission to deploy, publish, send messages, or upload any private source/data elsewhere. Research public sources as needed and distinguish observed facts from hypotheses. Return a recommendation that can be rejected by evidence, not a promise of perfect accuracy.
