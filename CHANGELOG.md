<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Engines. Proprietary: see LICENSE. -->
# Changelog

## 0.1.0-SNAPSHOT (unreleased, written unbuilt)

* `engine-capacity`: the capacity planner as an engine with no inputs; one evaluator of `capacity/planner.yaml`; explanation and workbook
  generated from the expression trees; kit cases recorded from Drishti's browser evaluator.
* `engine-genomics-workbench`: the genomics showcase's cohort figures, cohort matrix, activity by round and leaderboard; kit cases recorded
  from Drishti's showcase fixture and answers.
* `engine-indicators`: derived economic figures (change, percentage change, compound growth, mean, spread, z-score, rank); kit cases from an
  independent oracle.
* `engine-query` (EN-51, the E0 paper-review object of EN-53): pivot views, model queries and derived-kind declarations read into one
  plan; mergeable aggregates as push-down parts (grouped scan when offered, else an exact scan), holistic ones from a scan; totals and
  subtotals as their own units, top N with Others, `calc`, `share_of`, time windows, calculation groups, show-as, money at given rates;
  tidy `cells` and `members`, `changes` through `peer`; explanations and a formula workbook; `exact-path`; kit cases recorded from
  Drishti's formula-parity answers and hand cases. Design: `docs/QUERY_ENGINE.md`.
* Every engine implements the lifecycle (`init`, `start`, `suspend`, `resume`, `shutdown`), `health()` and `stats()`.
* Guards: header and file cap, launchers, names no host, no mutable statics, the shape of an engine module.
