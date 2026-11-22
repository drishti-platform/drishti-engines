#!/usr/bin/env python3
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Engines. Proprietary: see LICENSE.
"""Records the genomics-workbench kit cases from Drishti's own showcase code, so parity with the code it replaces can be proven later.

Inputs are the fixture of Drishti's ShowcaseTest (modules/genomics/java/genomics/src/test/resources/showcase/<kind>.jsonl: 60 samples, 300
variants, a 40-residue protein, three design rounds); the expected answers are Drishti's committed answers.json, which its own test proves
equal to what GenomicsBoard and ProteinBench answer over that fixture. Nothing here computes a figure.

    python3 tools/genomics_cases.py --drishti <path of a Drishti checkout>
"""
import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CASES = ROOT / "java" / "engine-genomics-workbench" / "tck" / "cases" / "genomics-workbench"
KINDS = ["study-sample", "variant-call", "study-variant", "study-gene", "cnv-segment", "protein", "design", "design-mutation", "design-round"]


def q(v) -> str:
    return json.dumps(v, ensure_ascii=False)


def rows(d: Path, kinds, indent="    "):
    out = []
    for k in kinds:
        out.append(f"{indent}{k}:")
        out.append(f"{indent}  rows:")
        for line in (d / f"{k}.jsonl").read_text(encoding="utf-8").splitlines():
            if line.strip():
                out.append(f"{indent}    - {json.dumps(json.loads(line), ensure_ascii=False)}")
    return out


GENOMICS = ["study-sample", "variant-call", "study-variant", "study-gene", "cnv-segment"]
PROTEIN = ["protein", "design", "design-mutation", "design-round"]


def header(suite: str, description: str, d: Path, kinds) -> list:
    return [
        "# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Engines. Proprietary: see LICENSE.",
        "# Written by tools/genomics_cases.py from Drishti's showcase fixture and its committed answers.json (the answers of GenomicsBoard and ProteinBench).",
        f"suite: genomics-workbench/{suite}",
        "engine: genomics-workbench",
        'since: "1.0"',
        f"description: {description}",
        "given:",
        "  inputs:",
    ] + rows(d, kinds) + ["cases:"]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--drishti", required=True)
    d = Path(ap.parse_args().drishti) / "modules/genomics/java/genomics/src/test/resources/showcase"
    answers = json.loads((d / "answers.json").read_text(encoding="utf-8"))
    CASES.mkdir(parents=True, exist_ok=True)
    s = answers["cohorts/ONC-1/summary"]
    o = answers["cohorts/ONC-1/oncoprint"]
    r = answers["proteins/HLXK7/rounds"]
    lb = answers["proteins/HLXK7/leaderboard"]

    g = header("cohort", "The showcase fixture's samples, calls, variants, genes and copy-number segments at the answers Drishti's own code gives; refusals.", d, GENOMICS)
    g += [
        "  - id: summary-of-the-whole-cohort",
        "    source: \"Drishti answers.json cohorts/ONC-1/summary (GenomicsBoard.summary over the showcase fixture)\"",
        "    reference: true",
        "    request: { outputs: [ summary ], params: { cohort: ONC-1, gene: HLXK7 }, visibility: all, seed: 0 }",
        "    expect:",
        "      status: complete",
        "      tables:",
        "        summary:",
        "          columns: [ COHORT, SELECTION, COHORT_SAMPLES, SAMPLES, RESPONDERS, RESPONSE_RATE, ALTERED, ALTERED_RATE, RESPONSE_IF_ALTERED, RESPONSE_IF_NOT ]",
        "          rows:",
        "            - " + q([s["cohort"], s["selection"], s["cohortSamples"], s["samples"], s["responders"], s["responseRate"], s["altered"], s["alteredRate"],
                              s["responseIfAltered"], s["responseIfNot"]]),
        "  - id: cohort-matrix",
        "    source: \"Drishti answers.json cohorts/ONC-1/oncoprint with 24 genes and HLXK7 asked (GenomicsBoard.oncoprint)\"",
        "    request: { outputs: [ matrix-genes, matrix-cells ], params: { cohort: ONC-1, gene: HLXK7, genes-wanted: 24 }, visibility: all, seed: 0 }",
        "    expect:",
        "      status: complete",
        "      tables:",
        "        matrix-genes:",
        "          columns: [ RANK, GENE, SAMPLES, PCT ]",
        "          rows:",
    ]
    for i, x in enumerate(o["altered"], 1):
        g.append("            - " + q([i, x["GENE"], x["SAMPLES"], x["PCT"]]))
    g += ["        matrix-cells:", "          columns: [ ORDINAL, SAMPLE_ID, GENE, ALTERATION ]", "          rows:"]
    for i, c in enumerate(o["rows"], 1):
        g.append("            - " + q([i, c["SAMPLE_ID"], c["GENE"], c["ALTERATION"]]))
    g += [
        "  - id: a-box-of-three-numbers-is-refused",
        "    source: \"Drishti ShowcaseTest: box is x0,x1,y0,y1 (four numbers)\"",
        "    request: { outputs: [ summary ], params: { cohort: ONC-1, box: \"1,2,3\" }, visibility: all, seed: 0 }",
        "    expect: { refused: ENG-3002 }",
        "  - id: a-request-without-rows-of-samples-is-refused",
        "    source: \"section 8.6 rule 1\"",
        "    request: { outputs: [ summary ], params: { cohort: ONC-1 }, visibility: {}, seed: 0 }",
        "    expect: { refused: ENG-4002 }",
        "",
    ]
    (CASES / "cohort.yaml").write_text("\n".join(g), encoding="utf-8")

    p = header("designs", "The showcase fixture's protein, designs, their changes and rounds at the answers Drishti's own code gives; a missing table; refusals.", d, PROTEIN)
    p += [
        "  - id: activity-by-round",
        "    source: \"Drishti answers.json proteins/HLXK7/rounds (ProteinBench.rounds)\"",
        "    reference: true",
        "    request: { outputs: [ rounds ], params: { protein: HLXK7 }, visibility: all, seed: 0 }",
        "    expect:",
        "      status: complete",
        "      tables:",
        "        rounds:",
        "          columns: [ PROTEIN, ROUND, LOW, Q1, MEDIAN, Q3, HIGH, N, BEST, METHOD ]",
        "          rows:",
    ]
    for x in r["rows"]:
        p.append("            - " + q([r["protein"], int(x["ROUND"]), x["LOW"], x["Q1"], x["MEDIAN"], x["Q3"], x["HIGH"], x["N"], x["BEST"], x["METHOD"]]))
    p += [
        "  - id: leaderboard",
        "    source: \"Drishti answers.json proteins/HLXK7/leaderboard (ProteinBench.leaderboard, 20 designs)\"",
        "    request: { outputs: [ leaderboard ], params: { protein: HLXK7, n: 20 }, visibility: all, seed: 0 }",
        "    expect:",
        "      status: complete",
        "      tables:",
        "        leaderboard:",
        "          columns: [ RANK, DESIGN_ID, ROUND, ACTIVITY, DELTA_TM, N_CHANGES, MODEL_SCORE, STATUS, PARENT, CHANGES ]",
        "          rows:",
    ]
    for x in lb["rows"]:
        p.append("            - " + q([x["RANK"], x["DESIGN_ID"], x["ROUND"], x["ACTIVITY"], x["DELTA_TM"], x["N_CHANGES"], x["MODEL_SCORE"], x["STATUS"], x["PARENT"], x["CHANGES"]]))
    p += [
        "  - id: no-design-table",
        "    source: \"section 8.2: a table that is not there is an answer, naming it\"",
        "    given: { inputs: { design: absent } }",
        "    request: { outputs: [ rounds ], params: { protein: HLXK7 }, visibility: all, seed: 0 }",
        "    expect: { status: not-found, missing: [ { input: design, outputs: [ rounds ] } ] }",
        "  - id: an-unknown-protein-is-refused",
        "    source: \"Drishti ProteinBench.protein: no protein X (there are ...)\"",
        "    request: { outputs: [ rounds ], params: { protein: NOPE }, visibility: all, seed: 0 }",
        "    expect: { refused: ENG-3002 }",
        "",
    ]
    (CASES / "designs.yaml").write_text("\n".join(p), encoding="utf-8")
    print(f"wrote {CASES}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
