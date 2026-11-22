<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Engines. Proprietary: see LICENSE. -->
# Changelog

## 0.1.0-SNAPSHOT (unreleased, written unbuilt)

* `engine-capacity`: the capacity planner as an engine with no inputs; one evaluator of `capacity/planner.yaml`; explanation and workbook
  generated from the expression trees; kit cases recorded from Drishti's browser evaluator.
* `engine-genomics-workbench`: the genomics showcase's cohort figures, cohort matrix, activity by round and leaderboard; kit cases recorded
  from Drishti's showcase fixture and answers.
* `engine-indicators`: derived economic figures (change, percentage change, compound growth, mean, spread, z-score, rank); kit cases from an
  independent oracle.
* Every engine implements the lifecycle (`init`, `start`, `suspend`, `resume`, `shutdown`), `health()` and `stats()`.
* Guards: header and file cap, launchers, names no host, no mutable statics, the shape of an engine module.
