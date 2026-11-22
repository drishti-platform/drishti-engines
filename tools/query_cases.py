#!/usr/bin/env python3
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Engines. Proprietary: see LICENSE.
"""Writes the query engine's formula-parity kit cases from Drishti's recorded answers.

Drishti's PivotFormulaParityTest asks every formula of formula-parity/corpus.yaml as one view (rows REGION, columns DESK, every cell and
total) over PivotFixture's seeded trades table (3,000 rows from java.util.Random(42)), and compares with answers.json, recorded on the
old parser. This tool copies corpus.yaml and answers.json beside the cases (java/engine-query/tck/parity/formula-parity/), writes the
fixture's rows with a port of java.util.Random, and writes one kit case per formula whose expected cells are the recorded ones (the SQL
path's, DuckDB's: compared under the NUMBER column's relative:1e-12).

    python3 tools/query_cases.py --drishti <a Drishti checkout>          # copies the corpus from the checkout, then writes the cases
    python3 tools/query_cases.py                                         # writes the cases from the copies already here

Standard library only. The port of nextGaussian uses math.log and math.sqrt where Java uses StrictMath (fdlibm); a difference in the
last bit would change a P&L only if it fell exactly on a cent's half, and the kit would show it as a different cell.
"""

import argparse
import json
import math
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODULE = ROOT / "java" / "engine-query"
PARITY = MODULE / "tck" / "parity" / "formula-parity"
CASES = MODULE / "tck" / "cases" / "query" / "formula-parity.yaml"
SOURCE = "compute/drishti-bicompute/src/test/resources/formula-parity"

REGIONS = ["AMERICAS", "APAC", "EMEA"]
DESKS = ["RATES", "FX", "CREDIT", "EQUITY", "COMMOD"]
CCYS = ["USD", "EUR", "GBP", "JPY"]
ROWS = 3000
HEADER = "# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Engines. Proprietary: see LICENSE.\n"


class JavaRandom:
    """java.util.Random, bit for bit (nextInt, nextBoolean, nextDouble, nextGaussian)."""

    MASK = (1 << 48) - 1

    def __init__(self, seed):
        self.seed = (seed ^ 0x5DEECE66D) & self.MASK
        self.next_gaussian = None

    def next(self, bits):
        self.seed = (self.seed * 0x5DEECE66D + 0xB) & self.MASK
        r = self.seed >> (48 - bits)
        return r - (1 << bits) if bits == 32 and r >= 1 << 31 else r

    def next_int(self, bound):
        if bound & -bound == bound:
            return (bound * self.next(31)) >> 31
        while True:
            bits = self.next(31)
            val = bits % bound
            if bits - val + (bound - 1) < (1 << 31):
                return val

    def next_boolean(self):
        return self.next(1) != 0

    def next_double(self):
        return ((self.next(26) << 27) + self.next(27)) * (1.0 / (1 << 53))

    def next_gauss(self):
        if self.next_gaussian is not None:
            g, self.next_gaussian = self.next_gaussian, None
            return g
        while True:
            v1 = 2 * self.next_double() - 1
            v2 = 2 * self.next_double() - 1
            s = v1 * v1 + v2 * v2
            if 0 < s < 1:
                break
        m = math.sqrt(-2 * math.log(s) / s)
        self.next_gaussian = v2 * m
        return v1 * m


def java_round(x):
    return math.floor(x + 0.5)


def fixture():
    """PivotFixture's rows, in the order the test staged them."""
    r = JavaRandom(42)
    out = []
    for i in range(ROWS):
        region = REGIONS[r.next_int(3)]
        desk = "FX" if region == "APAC" and r.next_boolean() else DESKS[r.next_int(5)]
        ccy = CCYS[r.next_int(4)]
        date = "2026-%02d-%02d" % (1 + r.next_int(10), 1 + r.next_int(28))
        notional = java_round(r.next_double() * 1e7) / 100.0
        pnl = None if i % 97 == 0 else java_round((r.next_gauss() * 5e4) * 100) / 100.0
        cpty = "CP-%d" % r.next_int(150)
        salary = 1000.0 + r.next_int(500)
        rate = java_round(r.next_double() * 500) / 100.0
        out.append({"REGION": region, "DESK": desk, "CCY": ccy, "TRADE_DATE": date, "NOTIONAL": notional, "PNL": pnl, "CPTY": cpty,
                    "SALARY": salary, "RATE_PCT": rate})
    return out


def corpus(path):
    """The corpus's formulas as written and as asked (the rachana text when the migration rewrote it)."""
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        m = re.match(r'^- \{formula: "((?:[^"\\]|\\.)*)"(.*)\}\s*$', line)
        if not m:
            continue
        formula = m.group(1).replace('\\"', '"')
        r = re.search(r'rachana: "((?:[^"\\]|\\.)*)"', m.group(2))
        out.append((formula, r.group(1).replace('\\"', '"') if r else formula))
    return out


def scalar(v):
    if v is None:
        return "null"
    if isinstance(v, float):
        return repr(v)
    if isinstance(v, (int,)):
        return str(v)
    return json.dumps(v)


def flow(m):
    return "{ " + ", ".join("%s: %s" % (k, scalar(v)) for k, v in m.items()) + " }"


def write(rows, entries, answers):
    lines = [HEADER, "# Written by tools/query_cases.py from Drishti's formula-parity corpus and answers.json (PivotFormulaParityTest): do not edit.\n",
             "suite: query/formula-parity\n", "engine: query\n", 'since: "0.1"\n',
             "description: Every formula of Drishti's pivot formula parity corpus over regions by desks with every total, against the answers recorded on the old parser.\n",
             "given:\n", "  snapshot: { date: \"2026-10-30\" }\n", "  inputs:\n", "    tables:\n", "      tables:\n", "        trades:\n", "          rows:\n"]
    for r in rows:
        lines.append("            - " + flow(r) + "\n")
    lines.append("cases:\n")
    for n, (formula, asked) in enumerate(entries):
        want = answers[formula]["sql"]
        cells = []
        for key, value in want.items():
            row, col = key.split("|", 1)
            cells.append((row, col, value))
        cells.sort(key=lambda c: (c[0], c[1]))
        lines.append("  - id: f%02d\n" % (n + 1))
        lines.append("    source: %s\n" % json.dumps("Drishti formula-parity answers.json, SQL path: " + formula))
        if n == 0:
            lines.append("    reference: true\n")
        view = {"source": "trades", "rows": ["REGION"], "columns": ["DESK"], "values": [{"measure": asked, "as": "F"}]}
        lines.append("    request: { outputs: [ cells ], params: { view: %s }, visibility: all, seed: 0 }\n" % json.dumps(view))
        lines.append("    expect:\n      status: complete\n      tables:\n        cells:\n          subset: true\n"
                     "          columns: [ ROW, COLUMN, VALUE, NUMBER ]\n          rows:\n")
        for row, col, value in cells:
            lines.append("            - [ %s, %s, F, %s ]\n" % (json.dumps(row), json.dumps(col), scalar(value)))
    CASES.parent.mkdir(parents=True, exist_ok=True)
    CASES.write_text("".join(lines), encoding="utf-8")


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--drishti", help="a Drishti checkout to copy corpus.yaml and answers.json from")
    a = p.parse_args()
    PARITY.mkdir(parents=True, exist_ok=True)
    if a.drishti:
        for name in ("corpus.yaml", "answers.json"):
            shutil.copyfile(Path(a.drishti) / SOURCE / name, PARITY / name)
    entries = corpus(PARITY / "corpus.yaml")
    answers = json.loads((PARITY / "answers.json").read_text(encoding="utf-8"))
    missing = [f for f, _ in entries if f not in answers]
    if missing:
        raise SystemExit("formulas without a recorded answer: %s" % missing)
    write(fixture(), entries, answers)
    print("%d cases, %d fixture rows -> %s" % (len(entries), ROWS, CASES.relative_to(ROOT)))


if __name__ == "__main__":
    main()
