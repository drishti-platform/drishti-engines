---
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Engines. Proprietary: see LICENSE.
name: Drishti Engines
summary: >-
  The first-party platform engines of the Drishti family, one Maven module and one JAR each: capacity (the capacity planner, no inputs),
  genomics-workbench (the genomics showcase's computations) and indicators (derived economic figures). Each is a pure, deterministic,
  explained function from contract tables to tables, with its descriptor (and the product id a licence grants) and its kit cases.
status: 0.1.0-SNAPSHOT, milestone M2 of combined i8 (EN-50, EN-52, EN-55); written unbuilt, nothing here has been compiled or run yet
coordinates: {groupId: com.ash.engines, artifacts: [engine-capacity, engine-genomics-workbench, engine-indicators, engine-query]}
java: 21
modules: [engine-capacity, engine-genomics-workbench, engine-indicators, engine-query, engines-guards]
depends-on: [engine-api (provided: drishti-engine develop), tools-xlsx 1.1.0 (provided: the workbook writer), engine-tck and engine-cli (test: the kit)]
commands: {build: "cd java && ./mvnw clean verify", kit: "java -jar engine-tck-<v>-cli.jar tck run --engine <jar> --cases <dir> --out <dir>"}
consumers: [Drishti (the host, from H2): the base distribution includes these JARs in <home>/lib/engines]
design: docs/plans/ENGINE_INTEGRATION_DESIGN.md in the Drishti repository (sections 2, 14 and the owner direction of 2026-11-22)
licence: proprietary (LICENSE)
---

# Drishti Engines

First-party **platform engines**, one module and one JAR each, built on the
[drishti-engine](https://github.com/drishti-platform/drishti-engine) API and proven by its kit. None is compiled into a host: a host loads
each JAR from its lib folder, checks the versions it was built against and the licence's product id, and binds it by configuration.

| Engine | JAR | Product id | Inputs | Outputs | What it replaces |
|---|---|---|---|---|---|
| `capacity` | `engine-capacity` | `drishti-platform` | none (a calculator over parameters) | `figures`, `lines`, `catalogue` | the planner's three evaluators of one file (browser, `tools/sizing_planner.py`, the workbook template): one evaluator of `capacity/planner.yaml`, the explanation and the workbook generated from its steps (EN-55) |
| `genomics-workbench` | `engine-genomics-workbench` | `drishti-genomics` | nine contracts of the genomics and protein packs | `summary`, `matrix-genes`, `matrix-cells`, `rounds`, `leaderboard` | `GenomicsBoard` and `ProteinBench` figures (summary, cohort matrix, activity by round, leaderboard) |
| `indicators` | `engine-indicators` | `drishti-economy` | `economy/observation@1` (a flat observation kind) | `series`, `ranks` | nothing: the economy module computes no derived series today, so this is born as an engine |
| `query` | `engine-query` | `drishti-platform` | one open input, `tables`: any table the request's document names, read as the caller | `cells`, `members`, `changes` | Drishti's two query stacks, the pivot core and the BI query engine (each with its own DuckDB), and derived kinds: one planner, one executor path through the host's grouped scan (EN-51). Design and E0 open points: [docs/QUERY_ENGINE.md](docs/QUERY_ENGINE.md) |

## Parity data

Each engine's kit cases carry answers recorded from the code it replaces, so parity can be proven later by running the kit against the host.

* `capacity`: `tools/capacity_cases.py --drishti <checkout>` records the figures of Drishti's own browser evaluator
  (`drishti_capacity.js` under Node) over `capacity/planner.yaml` at the three shapes of Drishti's committed test vectors (production, benchmark,
  large Java 25 with exposure) and two collector shapes, at full precision. The planner file is a verbatim copy; this repository becomes its home.
* `genomics-workbench`: `tools/genomics_cases.py --drishti <checkout>` copies the inputs of Drishti's `ShowcaseTest` fixture (60 samples, 300
  variants, a 40-residue protein, three design rounds) into the cases and the expected tables from Drishti's committed `answers.json`.
* `query`: `tools/query_cases.py [--drishti <checkout>]` copies Drishti's pivot formula-parity corpus and `answers.json` (76 formulas over
  regions by desks with every total) under `java/engine-query/tck/parity/`, writes `PivotFixture`'s 3,000 seeded rows with a port of
  `java.util.Random`, and one case per formula; `tck/cases/query/pivot.yaml` holds the hand cases.
* `indicators`: `tools/indicators_cases.py` writes cases from an independent Python oracle over nine series (no Drishti code to record).

`tools/capacity_descriptor.py` writes the capacity descriptor from the planner file (every planner input is a parameter), so the two cannot drift.

## Build and run in the IDE

JDK 21. Build the engine API first (`drishti-engine`, `develop`) so `engine-api`, `engine-tck` and `engine-cli` are in the local repository, then
`cd java && ./mvnw clean verify`. IntelliJ launchers are in `run-configs/`, VS Code tasks of the same names in `drishti-engines.code-workspace`.

## Engine obligations

Every engine here implements, besides `descriptor`, `session` and `notes`:

- the five lifecycle methods `init`, `start`, `suspend`, `resume`, `shutdown` (specification 7.1);
- `Health health()`: UP, DEGRADED, DOWN or SUSPENDED, in agreement with the lifecycle state, reasons in words (7.4);
- `EngineStats stats()`: requests asked/answered/refused/failed/cancelled, latency p50/p95/p99, rows and bytes in and out, cache hits and
  misses, sessions open, queue depth, last activity, since start and a recent window (7.5). `EngineMeter` (engine-api) records them.

Both are cheap, never block and never do I/O. The kit's observability law proves them; an engine that fails it is not released. The same
holds for every engine in drishti-risk.

Author: Ashutosh Sinha. Proprietary. All rights reserved (see [LICENSE](LICENSE)).
