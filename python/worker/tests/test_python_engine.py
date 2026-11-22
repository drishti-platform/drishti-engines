# Drishti Engines · first-party engines by contract.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL.
#
# This file is the confidential and proprietary property of Ashutosh Sinha.
# Unauthorised copying, use, modification, distribution or disclosure of this
# file, via any medium, is strictly prohibited except with the express prior
# written permission of the copyright holder.
#
# See the LICENSE file in the root of this repository for the full terms.

"""The ``python`` engine class in process (no sandbox: the strict-sandbox runs are in test_items.py): the approved hash is checked, each
output shapes its rows, a failure of the author's code is an error row, a pivot function split over shards equals one pass, and the
static check names its problems."""
from __future__ import annotations

import math

import pytest

from engine_python import arrow
from engine_python.contract import ComputeError, Inputs, Progress, Request
from engine_python.engine import PythonEngine, code_hash

FRAME = arrow.Table([arrow.utf8("desk", ["A", "A", "B"]), arrow.float64("pnl1d", [1.0, 2.0, 5.0]), arrow.utf8("id", ["1", "2", "3"])])


def run(tmp_path, kind, code, files=None, **params):
    scratch = tmp_path / "r1"
    (scratch / "in").mkdir(parents=True)
    rel = {}
    for name, table in (files if files is not None else {"f": FRAME}).items():
        arrow.write_file(table, str(scratch / "in" / (name + ".arrows")))
        rel[name] = "in/" + name + ".arrows"
    req = Request(id="r1", kind=kind, params={"code": code, "frame": "f", **params}, user="u", roles=(), view_signature="", cls="interactive",
                  seed=0, scratch=str(scratch))
    return PythonEngine().compute(req, Inputs(str(scratch), rel), Progress(lambda d: None))


def item(text, interface, **more):
    return {"name": "t", "version": "1", "hash": code_hash(text), "text": text, "interface": interface, "packages": ["pandas"], **more}


def test_the_text_must_hash_to_its_approval(tmp_path):
    c = item("def compute(t, p):\n    return 1\n", "measure")
    c["text"] += "# changed after approval\n"
    with pytest.raises(ComputeError, match="does not hash"):
        run(tmp_path, "measure", c, inputs=["pnl1d"], group_by=["desk"])


def test_an_output_of_another_interface_is_refused_in_words(tmp_path):
    c = item("def render(t, p):\n    return {}\n", "visual")
    with pytest.raises(ComputeError, match="is a visual item"):
        run(tmp_path, "measure", c)


def test_a_measure_is_one_number_per_group(tmp_path):
    pytest.importorskip("pandas")
    c = item("def compute(table, params):\n    return float(table['pnl1d'].sum()) * params.get('k', 1)\n", "measure")
    r = run(tmp_path, "measure", c, inputs=["pnl1d"], group_by=["desk"], params={"k": 2})
    assert [(x["GROUP"], x["VALUE"]) for x in r.rows] == [('["A"]', 6.0), ('["B"]', 10.0)]
    assert r.rows[0]["CODE"] == c["hash"] and r.meta["code"]["name"] == "t"


def test_a_failure_of_the_authors_code_is_an_error_row_with_their_traceback(tmp_path):
    pytest.importorskip("pandas")
    r = run(tmp_path, "visual", item("def render(t, p):\n    return 1 / 0\n", "visual"))
    e = r.rows[0]["error"]
    assert e["kind"] == "failed" and "ZeroDivisionError" in e["error"] and "<python-item>" in e["traceback"]


def test_a_column_is_one_value_per_row(tmp_path):
    pytest.importorskip("pandas")
    r = run(tmp_path, "column", item("def derive(rows, params):\n    return rows['pnl1d'] * 2\n", "column"), inputs=["pnl1d"])
    assert [x["VALUE"] for x in r.rows] == [2.0, 4.0, 10.0] and all(x["TEXT"] is None for x in r.rows)


WAVG = '''
from drishti_pivot import function, Mergeable
import math

@function(name="wavg", kind=Mergeable, inputs={"r": "float64", "w": "float64"})
class WeightedAverage:
    def start(self, params):
        return {"swr": 0.0, "sw": 0.0}
    def add(self, s, batch):
        r, w = batch.list("r"), batch.list("w")
        return {"swr": s["swr"] + math.fsum(a * b for a, b in zip(r, w)), "sw": s["sw"] + math.fsum(w)}
    def merge(self, a, b):
        return {"swr": a["swr"] + b["swr"], "sw": a["sw"] + b["sw"]}
    def finish(self, s):
        return None if s["sw"] == 0 else s["swr"] / s["sw"]
'''


def test_a_function_over_shards_equals_one_pass(tmp_path):
    n = 1000
    frame = arrow.Table([arrow.utf8("g", ["x" if i % 3 else "y" for i in range(n)]), arrow.float64("r", [0.01 * (i % 17 - 8) for i in range(n)]),
                         arrow.float64("w", [1.0 + i % 13 for i in range(n)])])
    c = item(WAVG, "function", entry="WeightedAverage")
    one = {x["GROUP"]: x["VALUE"] for x in run(tmp_path / "a", "function", c, files={"f": frame}, group_by=["g"], shards=1, **{"batch-rows": 97}).rows}
    many = {x["GROUP"]: x["VALUE"] for x in run(tmp_path / "b", "function", c, files={"f": frame}, group_by=["g"], shards=5, **{"batch-rows": 97}).rows}
    assert set(one) == {'["x"]', '["y"]'} and all(math.isclose(one[k], many[k], rel_tol=1e-12) for k in one)
    x_rows = [i for i in range(n) if i % 3]
    want = math.fsum(0.01 * (i % 17 - 8) * (1.0 + i % 13) for i in x_rows) / math.fsum(1.0 + i % 13 for i in x_rows)
    assert math.isclose(one['["x"]'], want, rel_tol=1e-12)


def test_a_function_with_a_forbidden_import_is_an_invalid_error_row(tmp_path):
    r = run(tmp_path, "function", item("import os\n" + WAVG, "function", entry="WeightedAverage"), group_by=["desk"])
    assert r.rows[0]["error"]["kind"] == "invalid" and "may import" in r.rows[0]["error"]["error"]


def test_the_static_check_names_its_problems_and_runs_nothing(tmp_path):
    bad = item("import os\ndef compute(t, p):\n    return eval('1')\n", "measure")
    rows = run(tmp_path, "check", bad, files={}).rows
    assert any("import of 'os'" in r["MESSAGE"] for r in rows) and any("eval" in r["MESSAGE"] for r in rows) and all(r["ITEM"] == "t" for r in rows)
    assert run(tmp_path / "ok", "check", item("def compute(t, p):\n    return 1\n", "measure"), files={}).rows == []
