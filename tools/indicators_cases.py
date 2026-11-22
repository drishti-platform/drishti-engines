#!/usr/bin/env python3
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Engines. Proprietary: see LICENSE.
"""Writes the indicators engine's kit cases. There is no Drishti code to record (the economy module computes no derived series): the expected
figures come from this independent oracle, plain Python floats in the same order of operations, over series chosen so that every power is exact
(ratios that are perfect squares, growth over 1 or 2 years). Run:  python3 tools/indicators_cases.py"""
import json
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "java" / "engine-indicators" / "tck" / "cases" / "indicators" / "series.yaml"

OBS = {  # (economy, indicator, unit) -> {year: value}
    ("A", "GDP", "USD bn"): {2020: 100.0, 2021: 110.0, 2022: 121.0, 2023: 133.0},
    ("B", "GDP", "USD bn"): {2021: 150.0, 2022: 160.0, 2023: 200.0},
    ("C", "GDP", "USD bn"): {2022: 130.0, 2023: 133.0},
    ("D", "GDP", "USD bn"): {2023: 90.0},
    ("E", "GDP", "USD bn"): {2022: 0.0, 2023: 5.0},
    ("F", "GDP", "USD bn"): {2021: 10.0, 2022: 10.0, 2023: 10.0},
    ("A", "GROWTH", "% a year"): {2021: 25.0, 2022: 50.0, 2023: 100.0},
    ("B", "GROWTH", "% a year"): {2021: 4.0, 2022: 5.0, 2023: 36.0},
    ("C", "GROWTH", "% a year"): {2021: 7.0, 2022: 7.0, 2023: 7.0},
}


def q(v) -> str:
    return json.dumps(v, ensure_ascii=False)


def series(econ, ind, unit, vals, years):
    ys = sorted(vals)
    v = [vals[y] for y in ys]
    n = len(v)
    latest = v[-1]
    prev = v[-2] if n >= 2 else None
    change = None if prev is None else latest - prev
    pct = None if prev is None or prev == 0 else (latest - prev) / abs(prev) * 100
    start = vals.get(ys[-1] - years)
    cagr = None if start is None or start <= 0 or latest <= 0 else ((latest / start) ** (1.0 / years) - 1) * 100
    s = 0.0
    for x in v:
        s += x
    mean = s / n
    sd = z = None
    if n >= 2:
        sq = 0.0
        for x in v:
            sq += (x - mean) * (x - mean)
        sd = math.sqrt(sq / (n - 1))
        z = (latest - mean) / sd if sd > 0 else None
    return [econ, ind, unit, ys[-1], n, latest, prev, change, pct, cagr, mean, sd, z]


def ranks(rows):
    by = {}
    for r in rows:
        by.setdefault(r[1], []).append((r[0], r[5]))
    out = []
    for ind in sorted(by):
        lst = sorted(by[ind], key=lambda e: (-e[1], e[0]))
        of = len(lst)
        rank = 0
        got = []
        for i, (e, lv) in enumerate(lst):
            if i == 0 or lv != lst[i - 1][1]:
                rank = i + 1
            pc = None if of == 1 else (of - rank) / (of - 1) * 100
            got.append([ind, e, rank, of, lv, pc])
        out += got
    return out


def case_rows(ind, years, only=None):
    rows = [series(e, i, u, vals, years) for (e, i, u), vals in sorted(OBS.items()) if i == ind and (only is None or e == only)]
    return sorted(rows, key=lambda r: (r[0], r[1]))


def inputs_block():
    lines = ["given:", "  inputs:", "    observation:", "      rows:"]
    for (e, i, u), vals in OBS.items():
        for y, v in sorted(vals.items()):
            lines.append("        - " + q({"ECONOMY": e, "INDICATOR": i, "YEAR": y, "VALUE": v, "UNIT": u}))
    return lines


def table(name, cols, rows):
    out = [f"        {name}:", f"          columns: [ {', '.join(cols)} ]", "          rows:"]
    out += ["            - " + q(r) for r in rows]
    return out


SC = ["ECONOMY", "INDICATOR", "UNIT", "LATEST_YEAR", "N", "LATEST", "PREVIOUS", "CHANGE", "PCT_CHANGE", "CAGR", "MEAN", "STDEV", "Z_SCORE"]
RC = ["INDICATOR", "ECONOMY", "RANK", "OF_COUNT", "LATEST", "PERCENTILE"]


def main() -> int:
    lines = [
        "# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Engines. Proprietary: see LICENSE.",
        "# Written by tools/indicators_cases.py: the figures of an independent Python oracle over series whose powers are exact.",
        "suite: indicators/series",
        "engine: indicators",
        'since: "1.0"',
        "description: Change, percentage change, compound growth, mean, spread, z-score and rank over nine series, with every figure that cannot be made left null.",
    ] + inputs_block() + ["cases:"]
    gdp = case_rows("GDP", 1)
    lines += [
        "  - id: gdp-over-one-year",
        "    source: \"independent Python oracle over the series above (growth over 1 year is the ratio, exactly)\"",
        "    reference: true",
        "    request: { outputs: [ series ], params: { years: 1, indicator: GDP }, visibility: all, seed: 0 }",
        "    expect:", "      status: complete", "      tables:",
    ] + table("series", SC, gdp) + [
        "  - id: gdp-ranks",
        "    source: \"independent Python oracle: largest first, equal values share the better rank\"",
        "    request: { outputs: [ ranks ], params: { indicator: GDP }, visibility: all, seed: 0 }",
        "    expect:", "      status: complete", "      tables:",
    ] + table("ranks", RC, ranks(gdp))
    gr = case_rows("GROWTH", 2)
    lines += [
        "  - id: growth-over-two-years",
        "    source: \"independent Python oracle: ratios 4, 9 and 1 over 2 years have exact square roots\"",
        "    request: { outputs: [ series ], params: { years: 2, indicator: GROWTH }, visibility: all, seed: 0 }",
        "    expect:", "      status: complete", "      tables:",
    ] + table("series", SC, gr)
    one = case_rows("GDP", 1, only="B")
    lines += [
        "  - id: a-viewer-of-economy-b-sees-only-b",
        "    source: \"filter equivalence, by hand: B alone in GDP has no percentile\"",
        "    request: { outputs: [ series ], params: { years: 1, indicator: GDP }, visibility: { observation: { eq: [ ECONOMY, B ] } }, seed: 0 }",
        "    expect:", "      status: complete", "      tables:",
    ] + table("series", SC, one) + [
        "  - id: a-viewer-of-economy-b-ranks-alone",
        "    source: \"filter equivalence, by hand: B alone in GDP is rank 1 of 1 with no percentile\"",
        "    request: { outputs: [ ranks ], params: { indicator: GDP }, visibility: { observation: { eq: [ ECONOMY, B ] } }, seed: 0 }",
        "    expect:", "      status: complete", "      tables:",
    ] + table("ranks", RC, ranks(one))
    lines += [
        "  - id: no-observation-table",
        "    source: \"section 8.2\"",
        "    given: { inputs: { observation: absent } }",
        "    request: { outputs: [ series ], params: { years: 1 }, visibility: all, seed: 0 }",
        "    expect: { status: not-found, missing: [ { input: observation, outputs: [ series ] } ] }",
        "  - id: zero-years-is-refused",
        "    source: \"descriptor: years has min 1\"",
        "    request: { outputs: [ series ], params: { years: 0 }, visibility: all, seed: 0 }",
        "    expect: { refused: ENG-3002 }",
        "  - id: a-request-without-rows-of-observations-is-refused",
        "    source: \"section 8.6 rule 1\"",
        "    request: { outputs: [ series ], params: { years: 1 }, visibility: {}, seed: 0 }",
        "    expect: { refused: ENG-4002 }",
        "",
    ]
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
