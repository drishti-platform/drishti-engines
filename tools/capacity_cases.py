#!/usr/bin/env python3
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Engines. Proprietary: see LICENSE.
"""Records the capacity engine's kit cases from Drishti's own evaluator, so parity with the code it replaces can be proven later.

The recorded answers are the figures of Drishti's browser evaluator (drishti-console/web/static/js/drishti_capacity.js, run under Node) over
the planner file and the three shapes of Drishti's committed test vectors (kernel/drishti-server/src/test/resources/capacity/test-vectors.json:
production, benchmark, large-java25-exposure), at full precision, plus two shapes for the collector choices. Only the cases are written; the
engine's own code is never consulted.

    python3 tools/capacity_cases.py --drishti <path of a Drishti checkout>      write java/engine-capacity/tck/cases/capacity/plan.yaml
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "java" / "engine-capacity"
PLANNER = MODULE / "src" / "main" / "resources" / "capacity" / "planner.yaml"
OUT = MODULE / "tck" / "cases" / "capacity" / "plan.yaml"
sys.path.insert(0, str(Path(__file__).resolve().parent))
from capacity_descriptor import kebab  # noqa: E402

NODE = r"""
global.window = global;
const fs = require('fs');
eval(fs.readFileSync(process.argv[1], 'utf8'));
const plan = JSON.parse(fs.readFileSync(0, 'utf8'));
const shapes = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const C = window.Drishti.capacity, out = {};
for (const [name, inputs] of Object.entries(shapes)) {
  const ev = C.evaluate(plan, inputs), v = {};
  const names = [];
  plan.results.forEach(b => b.rows.forEach(r => names.push(r.name)));
  plan.jvm.forEach(j => names.push(j.name));
  names.push('JvmCommand');
  plan.settings.forEach(s => names.push(s.name));
  names.push('YamlBlock');
  plan.warnings.forEach(w => names.push(w.name));
  (plan.calibration || []).forEach(c => names.push(c.name));
  names.forEach(n => { v[n] = ev.value(n); });
  out[name] = v;
}
process.stdout.write(JSON.stringify(out));
"""

SERVER_JAR = "drishti-server-2.9.11.i7a-exec.jar"
EXTRA = {
    "collector-g1": {"Positions": 4000000, "Trades": 4000000, "Collector": "G1"},
    "collector-zgc": {"Positions": 100000, "Trades": 100000, "Collector": "ZGC", "JavaVersion": 25},
}


def q(v) -> str:
    return json.dumps(v, ensure_ascii=False)


def numeric(v) -> str:
    return repr(float(v)) if isinstance(v, float) and not float(v).is_integer() else str(int(v)) if float(v).is_integer() and abs(v) < 1e15 else repr(float(v))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--drishti", required=True)
    a = ap.parse_args()
    d = Path(a.drishti)
    js = d / "drishti-console/web/static/js/drishti_capacity.js"
    vectors = json.loads((d / "kernel/drishti-server/src/test/resources/capacity/test-vectors.json").read_text(encoding="utf-8"))
    plan = yaml.safe_load(PLANNER.read_text(encoding="utf-8"))
    shapes = {}
    for name, s in vectors["shapes"].items():
        shapes[name] = dict(s["inputs"], ServerJar=s["results"]["ServerJar"])
    for name, inputs in EXTRA.items():
        shapes[name] = dict(inputs, ServerJar=SERVER_JAR)
    tmp = ROOT / "build-tmp"
    tmp.mkdir(exist_ok=True)
    (tmp / "shapes.json").write_text(json.dumps(shapes), encoding="utf-8")
    res = subprocess.run(["node", "-e", NODE, str(js), str(tmp / "shapes.json")], input=json.dumps(plan), capture_output=True, text=True, check=True)
    values = json.loads(res.stdout)
    inputs_by_name = {i["name"]: i for i in plan["inputs"]}
    text_names = {n for n, v in values["production"].items() if isinstance(v, str)}
    order = list(values["production"].keys())
    lines = [
        "# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Engines. Proprietary: see LICENSE.",
        "# Written by tools/capacity_cases.py: the figures of Drishti's own evaluator (drishti_capacity.js) over capacity/planner.yaml, at full precision.",
        "suite: capacity/plan",
        "engine: capacity",
        'since: "1.0"',
        "description: Every figure and line of the plan at five shapes (production, the 1,000,000-position benchmark, a large Java 25 server with exposure, and the two collector choices), the catalogue, and the refusals.",
        "given: {}",
        "cases:",
    ]
    for name, shape in shapes.items():
        params = {kebab(k): v for k, v in shape.items()}
        nums = [n for n in order if n not in text_names]
        lns = [n for n in order if n in text_names]
        ordinal = {n: i + 1 for i, n in enumerate(order)}
        lines += [
            f"  - id: {name}",
            f"    source: \"Drishti drishti_capacity.js over planner.yaml, shape {name} (test-vectors.json agrees to 1e-6)\"",
            f"    reference: {'true' if name == 'production' else 'false'}",
            "    request:",
            "      outputs: [ figures, lines ]",
            "      params: " + q(params),
            "      visibility: all",
            "      seed: 0",
            "    expect:",
            "      status: complete",
            "      tables:",
            "        figures:",
            "          columns: [ ORDINAL, SECTION, NAME, VALUE ]",
            "          rows:",
        ]
        section = {}
        for b in plan["results"]:
            for r in b["rows"]:
                section[r["name"]] = "results"
        for j in plan["jvm"]:
            section[j["name"]] = "jvm"
        section["JvmCommand"] = "command"
        for s in plan["settings"]:
            section[s["name"]] = "settings"
        section["YamlBlock"] = "yaml"
        for w in plan["warnings"]:
            section[w["name"]] = "warnings"
        for c in plan.get("calibration", []):
            section[c["name"]] = "calibration"
        for n in nums:
            lines.append(f"            - [ {ordinal[n]}, {section[n]}, {n}, {numeric(values[name][n])} ]")
        lines += ["        lines:", "          columns: [ ORDINAL, SECTION, NAME, TEXT ]", "          rows:"]
        for n in lns:
            lines.append(f"            - [ {ordinal[n]}, {section[n]}, {n}, {q(values[name][n])} ]")
    lines += [
        "  - id: catalogue",
        "    source: \"capacity/planner.yaml: the names, sections and units as the file states them\"",
        "    request: { outputs: [ catalogue ], params: {}, visibility: all, seed: 0 }",
        "    expect:",
        "      status: complete",
        "      tables:",
        "        catalogue:",
        "          subset: true",
        "          columns: [ ORDINAL, SECTION, NAME, UNIT ]",
        "          rows:",
    ]
    unit = {}
    for b in plan["results"]:
        for r in b["rows"]:
            unit[r["name"]] = r.get("unit", "")
    for j in plan["jvm"]:
        unit[j["name"]] = j.get("unit", "")
    unit["JvmCommand"] = "text"
    for s in plan["settings"]:
        unit[s["name"]] = s.get("unit", "")
    unit["YamlBlock"] = "text"
    for w in plan["warnings"]:
        unit[w["name"]] = w.get("unit", "")
    for c in plan.get("calibration", []):
        unit[c["name"]] = c.get("unit", "")
    for n in order:
        lines.append(f"            - [ {order.index(n) + 1}, {section[n]}, {n}, {q(unit[n])} ]")
    lines += [
        "  - id: a-negative-count-is-refused",
        "    source: \"descriptor: every count has min 0\"",
        "    request: { outputs: [ figures ], params: { positions: -1 }, visibility: all, seed: 0 }",
        "    expect: { refused: ENG-3002 }",
        "  - id: a-choice-outside-the-allowed-values-is-refused",
        "    source: \"descriptor: stress-on allows 0 and 1\"",
        "    request: { outputs: [ figures ], params: { stress-on: 2 }, visibility: all, seed: 0 }",
        "    expect: { refused: ENG-3002 }",
        "  - id: an-unknown-parameter-is-refused",
        "    source: \"section 3.2: the valid names are listed\"",
        "    request: { outputs: [ figures ], params: { cores: 8 }, visibility: all, seed: 0 }",
        "    expect: { refused: ENG-3001 }",
        "",
    ]
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
