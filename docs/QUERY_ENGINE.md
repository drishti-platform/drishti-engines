---
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Engines. Proprietary: see LICENSE.
title: The query engine
summary: >-
  Drishti's pivot core and its BI query engine folded into one engine, `query` (EN-51); derived kinds are its declarations; one
  push-down executor, the host's. The descriptor and the grouped scan are the E0 paper-review object (EN-53).
status: draft 1 (2026-11-22), for the E0 paper review; the module `java/engine-query` is written and not built
descriptor: java/engine-query/src/main/resources/META-INF/drishti-engine/query.engine.yaml
design: Drishti docs/plans/ENGINE_INTEGRATION_DESIGN.md sections 2.2 (rows 16 to 23 and 25), 2.3, 2.4, 2.6 (M3); EN-45 to EN-55
specification: drishti-engine spec/1/SPEC.md draft 3 (sections 3.2, 5.2, 6.2.1, 8.1 rule 6, 8.6, 12.4)
---

# The query engine

Today Drishti answers a pivot view and a BI visual with **two stacks**: the pivot core (`drishti-bicompute`, `server/pivot`,
`kernel.api.pivot`) and the BI query engine (`server/bi/engine`, `bi/plan`, `bi/sql`, `bi/layer`), each with its own in-process
DuckDB, its own cache and its own live path; derived kinds (`engine/source/derived`) are a third grouper. EN-51 makes them **one
engine**: one planner, one set of aggregate states, one finisher, one explanation; the SQL executor that groups at the source is the
**host's**, reached only through the grouped scan (`InputTable.offers` and `groups`), so access rules hold by construction and the
engine never holds a connection.

## 1. What the engine is, and what it is not

| The engine (`query`) | The host (Drishti), unchanged in kind |
|---|---|
| reads one of three documents into **one plan**: a pivot view, a model query, a derived-kind declaration | resolves the document's table names in its catalogue as the caller; applies row rules, masks, the column map and the row filter before delivery |
| plans the cell sets (cells, every open node's subtotals, axis totals, the grand total), level by level, only open nodes | owns the executor that answers a grouped scan (DuckDB today: `GROUP BY GROUPING SETS`, `FILTER (WHERE ...)`), and cancels it at the source |
| keeps aggregate states, merges them in a fixed order, finishes figures; formulas, `calc`, `share_of`, time windows, calculation groups, show-as, top N with Others, sorting, money at given rates | model definitions (relations, a model's joined table), table definitions, stores, caches by stamp, the small-cell rule on outputs, the busy gate |
| explains every figure from its own formula; writes the formula workbook | serves explain and workbook; audits |
| derived kinds' figures (count, sum, avg, median, min, max, distinct, first) per group | derived kinds' member lists, member rows, page trees: rows as they are (2.1 of the design) |

Not here, by the rule of section 2.1 of the design: rows as they are (`detail: true`, a page of a table: `QRY-4001`, the kernel's
read path), screen state (`slicers`, `formats`, conditional styles), the AI copilot, Calc in the browser, and people's Python (the
`python` engine of M4, reached later through a supplier port).

## 2. The descriptor in words

`java/engine-query/src/main/resources/META-INF/drishti-engine/query.engine.yaml`. Product id `drishti-platform` (a platform engine: the base distribution includes it). One snapshot dimension (`date`).

| Part | Declaration | Why |
|---|---|---|
| Input `tables` | `open: true`, `mode: request` | a query names tables its descriptor cannot list. Always read as the caller (8.6 rule 7), so the engine never sees a row the caller may not |
| Parameters `view`, `query`, `derived` | `document`, each with a JSON Schema | the three dialects. Exactly one is given per request (else `QRY-1001`). Formulas inside are Rachana-EL text: hermetic |
| Parameters `against`, `split-by` | `date`, `text` | the comparison output only |
| Output `cells` | tidy, fixed schema: `ROW`, `COLUMN`, `VALUE` (key), `NUMBER`, `TEXT`, `ROWS`, `NOTE` | the answer's shape depends on the question, an output's schema may not (6.2.2): see O1 |
| Output `members` | `AXIS`, `PATH` (key), `DEPTH`, `DIMENSION`, `MEMBER`, `PARENT`, `OPEN`, `OTHERS`, `ORDINAL` | the axes, sorted and cut by top N, so a screen lays the grid out without arithmetic |
| Output `changes` | `ROW`, `COLUMN`, `VALUE`, `BY` (key), `BEFORE`, `AFTER`, `CHANGE` | "why did this cell move": the same view at the other date through `EngineContext.peer`, split by one dimension |
| Option `exact-path` | `bool`, `forces: exact-path` | 8.1 rule 6: never a grouped scan; sums exact; `NUMBER` is `bits` |
| Options `max-cells`, `max-holistic-cells` | `int` | today's cell budget (20,000 and 5,000): refused with the count, never cut short |
| Options `batch-rows`, `explain-rows`, `workbook-rows` | `int` | no figure depends on them |
| Port `measures` | `uses`, contract `platform/query-cells@1` | the measure-provider port (design 2.3 point 2): replaces `PivotProvider`, `PivotSourceProvider`, `FigureSource`, `VarPivotProvider` |
| Capabilities | `explain`, `workbook` | every measure explained; `incremental`, `partition`, `progressive` wait for O8 to O10 |
| Codes | `QRY` | section 9 |

**Lifecycle, health, stats.** `init` reads the options; `start` acquires nothing (the engine holds no connection, thread or cache:
the executor is the host's); `suspend` and `resume` flip the state only; `shutdown` is idempotent and has nothing else to release.
`health()` follows the lifecycle; `stats()` is the `EngineMeter` every ask goes through.

**Access isolation.** The only input is open, so the host always runs this engine **per viewer** (8.6 rule 7, EN-47): every table is
read as the caller, `Visibility.rows` is `Filter.ALL` for it, and a session holds nothing between asks except caches keyed by
`Visibility.signature` and the input stamps. The certificate still lists `filter-equivalence`, `interleaving` and `isolation`: the
kit proves the engine does not depend on the setting. If a host ever delivers a non-`ALL` filter, the engine applies it: on a scan
as given, on a grouped scan translated to a Rachana-EL condition (`Filter.Eq` to `C == 'v'`, `In`, `Range`, `And`, `Or`, `Not`).

## 3. One plan from three documents

| Document | Becomes |
|---|---|
| `view` (a saved Drishti view is valid as it is) | rows, columns, values (formulas, `as`, `showAs`), named `measures`, `filters` (`in`, `notIn`), `where`, open nodes (`expanded`, `expandTo`), `sort`, `top`, `currency`, `calc-groups`, `calendar`, `totals`, `limit` |
| `query` (a BI visual) | `from` the source; `fields` the rows; `measures` the values (a model measure's name or a formula); `filters` (`eq` ... `not-null`) and `dates` lowered to one `where`; `orderBy` to sort; `limit`; `totals` (default false: plain rows); every level open |
| `derived` (a pack's derived kind) | `from` the source; `group-by` (and each `also-group-by`) a row dimension of its own grouping set; `fields` the values (`count`, `sum $.x`, ..., `distinct`, `first` by `id`); `where`; no totals |

A dimension is a field, a field with a date grain (`TRADE_DATE:month`, `year`, `quarter`, `week`, `day`), or a calculation group
(`calc:Time`). A model's relations are the host's: it offers the model's joined table as one open table, so the engine reads one
table per document (O11).

## 4. Measures

A value is a Rachana-EL formula over aggregates; an aggregate's argument is a Rachana-EL expression over a row (bare names are the
row's fields). The same text is pushed down: `Aggregate.of` and `Aggregate.where` are written by the engine from the parsed formula.

| Kind | Functions | Path |
|---|---|---|
| Mergeable, pushed | `sum`, `count()`, `count(x)`, `min`, `max`; `avg`, `wavg`, `variance`, `variance_pop`, `stddev`, `stddev_pop` (from sums, sums of squares and counts) | grouped scan when offered, else scan |
| Mergeable, scanned | `sum_once`, `count_once`, `avg_once` (once per distinct key) | scan |
| Holistic | `median`, `percentile(x, p)`, `distinct(x)` (count of distinct values), `mode`, `string_agg` | scan; budget `max-holistic-cells` |
| Context | `calc(agg, where=cond)` (an aggregate's own condition: pushed as `Aggregate.where`); `calc(agg, ignore=[dims])`, `share_of(agg, over=[dims])` = `agg / calc(agg, ignore=over)` (the coarser grouping set's state, planned beside) | as the aggregate |
| Time windows | `ytd`, `qtd`, `mtd`, `prior_period`, `same_period_last_year` `(agg, dateField)`: the window worked out once per request from the as-of date (the document's, else the snapshot's) and the document's `calendar` (weekends, holidays, fiscal-year start), then a `calc(agg, where=...)` | as the aggregate |
| Calculation groups | a dimension `calc:<group>`: each item a member; a value under item `I` is `I`'s template with `SELECTED` replaced by the value's formula | as the rewritten formula |
| Scalars | arithmetic, `? :`, `and`/`or`/`not`, comparisons, `in`, `abs`, `round`, `floor`, `ceiling`, `power`, `sqrt`, `ln`, `log10`, `exp`, `sign`, `coalesce`, `greatest`, `least`, `ratio` | in Java over finished aggregates |
| Show-as | `pct-grand`, `pct-row`, `pct-column`, `pct-parent-row`, `rank`, `ntile:n`, `diff:n:dim`, `pct-change:n:dim`; and the written forms `pct_of_total(...)`, `pct_of_row`, `pct_of_column`, `pct_of_parent` | over the answer's finished cells; never a second pass over rows |

**A total is its own set's state, never a sum of the figures shown** (today's rule, kept): a subtotal, a share's denominator and the
Others member of a top N are each a grouping set of their own. Others is asked as its own restriction (`not (DIM in [top members])`).

**Money.** A sum of a `currency.money` field over rows of several currencies is converted row by row at `currency.rates` into
`currency.report` (a row without a rate is left out and the cell says so); without `currency`, such a sum is refused (`QRY-3001`) once the catalogue gives fields' units (O16); until then the document names its money fields in `currency.money`.
The rates are in the document, so they are in the stamp.

## 5. The grouped scan contract (what the engine asks of the host)

```java
GroupScan(sets, aggregates, where, batchRows)   // sets: grouping sets over columns; where: Rachana-EL over a row, or null
Aggregate(name, fn, of, where)                  // fn: SUM, COUNT, MIN, MAX; of: Rachana-EL over a row (null: COUNT of rows)
```

1. **When.** The engine asks `offers(scan)` only when: `exact-path` is false; every value of the request is mergeable and pushed;
   every dimension of the request is a plain column (no grain, no expression key, no calculation-group dimension needs a column); the
   visibility for the input is translatable to EL. Otherwise it scans (`Scan` of exactly the columns the plan reads).
2. **What it sends.** One `Aggregate` per distinct (function, row expression, condition) of the plan, named `a0`, `a1`, ...; each
   `avg` sends `SUM` and `COUNT` of its argument with a not-null condition; each `variance`/`stddev` sends `SUM(x)`, `SUM(x * x)`,
   `COUNT(x)`. The view's filters and `where` are one `where`. Others is a second grouped scan whose `where` adds the restriction.
3. **What it expects back.** Per grouping set, batches of the set's columns then the aggregates in order; `SUM` and `MIN`/`MAX` as
   numbers (a whole-number sum MAY come as an integer), `COUNT` as an integer; a group with no qualifying row for an aggregate gives
   null for `SUM`/`MIN`/`MAX` and 0 for `COUNT`. The engine merges states in a fixed key order.
4. **Exactness.** Integer aggregates are exact on both paths. A floating-point `SUM` from a source is in the class
   `relative:1e-12` (a parallel source's order of addition is not fixed). Under `exact-path` the engine sums every `number`
   exactly: an order-free exact accumulator rounded once to the nearest double, so `NUMBER` is the same bits for any delivery order,
   batch size or thread count, and equals the kit's own exact executor (which sums in `BigDecimal`) bit for bit.
5. **Cancellation.** `Control.check()` once per batch on both paths; the host stops the statement at its source (5.2 rule 7).

**Interim workaround in draft 1 (O2):** a `Batch` does not say which grouping set it belongs to, so the engine sends one
`GroupScan` per grouping set (one statement per set instead of one `GROUPING SETS` statement). Correct, slower: O2 removes it.

## 6. Outputs

`cells`: one row per (row node, column node, value) of the answer. `ROW` and `COLUMN` are canonical coordinates as today's
`CellKey.canonical()`: `DIM=member` pairs sorted by dimension name and joined by `|`, `*` for the total along that axis; a member
that holds `|`, `=` or `\` is escaped with `\`; a null member is `(blank)`, the Others member `(others)`. `NUMBER` is the figure;
`TEXT` the figure when it is text (`string_agg`, `mode` of names, `first` of a text); `ROWS` the rows that made the cell (the host's
small-cell rule for aggregate-only roles reads it); `NOTE` why a figure is empty ("no values in the cell", "a rate is missing for
JPY", "the total is 0").

`members`: every node of both axes, the grand total included (`PATH` `*`, `DEPTH` 0), with its `ORDINAL` after sort and top N
(pre-order: a node before its children; `subtotals: above|below` is the screen's placement).

`changes`: see section 2. The other date's cells come from `EngineContext.peer(other).ask(...)` under the same visibility; the split
is a second plan with `split-by` added to the rows.

## 7. Explain and workbook

Every `NUMBER` of `cells` is explained by steps: one per aggregate of its formula (its rows: the `explain-rows` largest by absolute
value and one subtotal for the rest, with `sum($.rows, 'X') + $.rest` as the formula), then the formula itself over the aggregates'
values (it is already Rachana-EL), then the show-as step when there is one (`$.cell / $.total`). No opaque step for the functions of
section 4; holistic functions list every value of the cell, bounded by `explain-rows` with an opaque step past it (counted).
The workbook: `Data` holds the scanned rows of the cells shown (bounded by `workbook-rows`), `Main` one row per cell with
`SUMIFS`/`COUNTIFS`/`MINIFS`/`MAXIFS`/`AVERAGEIFS` over `Data` and the formula translated to Excel; defined names
`OUT_CELLS_NUMBER` (first row), as the specification asks.

## 8. Kit cases and parity

| Corpus | Source in Drishti | As kit cases |
|---|---|---|
| Formula parity: 99 formulas, each over regions by desks with totals (`answers.json`, the SQL path's cells and the Java path's grand total) | `compute/drishti-bicompute/src/test/resources/formula-parity/` (`corpus.yaml`, `answers.json`), `PivotFormulaParityTest`, the fixture `PivotFixture` (3,000 rows, `java.util.Random(42)`) | copied under `tck/parity/formula-parity/`; `FormulaParityCases` (test scope) writes the fixture rows with the same generator and one case per formula, the expected cells from `answers.json`, compared under `relative:1e-12` (the recording was DuckDB's); the exact path's bits are recorded once from the engine and kept additive |
| BI foundation parity | `kernel/drishti-server/src/test/resources/bi/foundation-parity/*.json`, `BiParityTest`, `BiFoundationParityTest` | listed for M3: they need the packs' data and models, so the recorder runs in Drishti (design 2.6: "a parity corpus recorded first") |
| Hand cases | this module | `tck/cases/query/*.yaml`: totals, subtotals, top N and Others, show-as, calc and share_of, time windows, calculation groups, money, a derived kind, missing table, refusals per own code |

## 9. Own codes

| Code | Kind | When |
|---|---|---|
| `QRY-1001` | REFUSED | not exactly one of `view`, `query`, `derived` given |
| `QRY-1003` | REFUSED | a formula that does not read (with the place) |
| `QRY-1004` | REFUSED | a function that is not known, or an aggregate inside an aggregate |
| `QRY-1005` | REFUSED | a calculation group, named measure or show-as that is not declared, or a measure that names itself |
| `QRY-2001` | LIMIT | more cells than `max-cells` (with the count) |
| `QRY-2002` | LIMIT | more cells than `max-holistic-cells` with a holistic value (with the count) |
| `QRY-3001` | REFUSED | reserved: money summed over several currencies without a conversion (needs the fields' units from the catalogue, O16) |
| `QRY-3002` | REFUSED | explain of a cell that is not in the answer |
| `QRY-3003` | LIMIT | a workbook over `workbook-rows` |
| `QRY-4001` | REFUSED | rows as they are (`detail: true`): the kernel's read path |

A table that is not there for the caller, or a field the document names that the caller's table does not have, is not an error: the
answer is `NOT_FOUND` with a `Missing` naming `tables:<table>` and the columns (8.2), so a masked column reads as missing, never as a
typo the caller could probe.

## 10. Open design points for the E0 review

| # | Point | Recommendation |
|---|---|---|
| O1 | An output's columns are fixed in the descriptor; a query's are not | **tidy outputs** (`cells`, `members`) as drafted: no specification change, every screen and Flight reads one schema; rejected: an "open output" whose schema comes from the document (a second schema mechanism, and the kit cannot check coverage) |
| O2 | `Batch` does not say which grouping set it belongs to | **add `int set()` to `Batch`** (or `GroupScan` delivering to one sink per set): additive, minor. Draft 1 sends one grouped scan per set |
| O3 | `GroupScan.sets` are over columns: date grains, `group-by: $.X` expressions and buckets cannot be pushed | **named key expressions**: `GroupScan(keys: Map<name, EL>, sets over those names, ...)`. Draft 1 scans when a key is an expression |
| O4 | `GroupScan.where` is EL text; the visibility is a `Filter` | keep EL; the engine translates a `Filter` (all six forms translate). No change |
| O5 | The push-down functions are SUM, COUNT, MIN, MAX: count distinct and sketches are scanned | add `COUNT_DISTINCT` (exact at every SQL source) in 1.x; sketch states (HLL, KLL bytes) only with a bench that needs them |
| O6 | Pushed floating-point sums are not bit-stable (design risk) | as drafted: `relative:1e-12` on `NUMBER`, `exact-path` gives bits via an exact accumulator; **measure** today's pivot figures across DuckDB thread counts at M3's first day (not measured yet). The recorded parity answers (DuckDB's) compare under the class; the exact path's bits are recorded anew |
| O7 | The host must find the table names a document holds (5.2 rule 5) | **a JSON Schema annotation `x-table: <input>`** on the properties that name tables (as drafted); specification 3.2 says a host reads it |
| O8 | What-if over an open input: 6.4 rule 7 wants a contract and `overlays` names an input whose contract the table has | allow `overlays: tables` on an open input with the overlaid table named in the document; the host checks the rows against the catalogue's schema of that table |
| O9 | `Incremental` and `partition` with an open input: `Changes.input` and `partition.unit` name a declared input and a column | `Changes.input` as `<input>:<table>` for an open input; `partition.unit: "@request"` (the request's first row dimension). Both additive; live and cluster gather of today's pivot need them at M3 |
| O10 | Progressive first answers sample at the source | an optional `GroupScan.sample` (fraction, seeded by `Request.seed`); until then interim answers only on the scan path |
| O11 | Joins and models | the host's table definitions: a model's joined table is one open table. The engine never joins |
| O12 | The small-cell rule of aggregate-only roles needs each cell's row count | the host reads `cells.ROWS`; the specification's output re-check names the column (`format: row-count`) |
| O13 | The measure-provider port's contract | `platform/query-cells@1` = this engine's `cells` columns; a provider is asked with the view and answers the values it names |
| O14 | Custom functions: Java plug-ins and SQL macros are not hermetic | Java plug-ins become engines behind a port; SQL macros are dropped (their bodies become Rachana-EL measures); Python functions are the `python` engine's (M4) |
| O16 | An open table's column types and units are not in the API (`InputTable.columns()` is names only): the engine reads a column as numbers when the batch holds numbers, else as text, and cannot know a money field | add `InputTable.schema()` (a `TableSchema` with types and units) for open inputs: additive, minor |
| O17 | `COUNT` with an `of` expression: the kit's executor counts rows and ignores `of`; a SQL source counts non-null values | the engine never sends `of` with `COUNT` (it writes `x != null` into the aggregate's `where`); the specification says `of` is null for `COUNT` |
| O15 | Text figures (`string_agg`, `distinct` lists of derived kinds) | `TEXT` column; a list as canonical JSON text |

## 11. What needs building (M3 work list)

1. The E0 review of this note and the descriptor; the specification additions O2, O3, O7 to O10 (all additive, minor).
2. Build and kit-run `java/query` (written, not built); `FormulaParityCases` generates the fixture and the parity cases.
3. The host: one grouped-scan executor (DuckDB) behind `InputTable.groups` for every table, the open-input binder, `x-table`
   resolution, the per-viewer session key; Drishti's two DuckDB executors (`DuckDbExecutor`, `DuckDbEngine`) become that one.
4. The parity recorder in Drishti over every pivot view and BI visual of the packs (three visibilities each), and the BI
   foundation corpus; the bench (`engine tck bench`) against today's pivot figures in `BENCHMARK.md`.
5. Rollups (aggregate awareness), live answers, what-if, progressive answers and cluster gather once O8 to O10 are decided; the
   measure-provider port bound to Risk's outputs; sketches (HLL, KLL, space-saving) for approximate answers.
