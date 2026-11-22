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

"""The ``python`` engine (EN-54): one engine, one pool, for every piece of Python code people write and an administrator approved.

The descriptor names this class (``worker: python:engine_python.engine:PythonEngine``); the pool manager (the Java side) starts the
workers, hands the rows over as Arrow files, enforces the wall time and cancels. The code itself is an INPUT of the engine, never
configuration: each request carries one approved code item (name, version, hash, text, interface, entry, packages), and the worker
refuses it when the text does not hash to the approved hash. What the code returns is shaped into the rows of the output asked.

Outputs (``req.kind``) and the interface of the code they run:

====================  ==================  ====================================================================================
output                interface           rows returned
====================  ==================  ====================================================================================
``measure``           measure             ``ITEM, GROUP (JSON list of the group's key), VALUE, CODE`` (one number per group)
``column``            column              ``ITEM, ROW, VALUE, TEXT`` (one value per input row)
``transform``         transform           ``ITEM, ROW, CELLS (JSON object of the row)``; ``meta.columns`` types the cells
``visual``            visual              ``ITEM, KIND, SPEC`` (a drishti-chart, ECharts or Vega-Lite spec, SVG or PNG as data)
``cell``              cell (notebook)     ``ITEM, STDOUT, RESULT`` (what it printed; its ``result`` table as JSON)
``function``          pivot function      ``ITEM, GROUP, VALUE, CODE`` (start / add / merge / finish over the group's rows)
``check``             any                 ``ITEM, N, LEVEL, CODE, PATH, MESSAGE`` (static checks; nothing runs)
====================  ==================  ====================================================================================

A failure of the author's code is an answer of the items runtime (``ok: false`` with its kind and the author's own traceback); this
class turns it into an ``error`` row the Java side raises as one typed refusal (``PYT-2001`` failed, ``PYT-2002`` invalid,
``PYT-2072`` limit, ``PYT-2073`` sandbox), with the traceback in the detail, never in the message.
"""
from __future__ import annotations

import hashlib
import json
import math
import os

from . import arrow, items, pivot_fn
from .contract import ComputeError, Engine, Inputs, Progress, Request, Result

ITEM_KINDS = {"measure": "measure", "column": "column", "transform": "transform", "visual": "visual", "cell": "cell"}
OUTPUTS = tuple(ITEM_KINDS) + ("function", "check")
DEFAULT_SHARDS = 1
MAX_SHARDS = 64


def code_hash(text: str) -> str:
    """The hash an approval holds: SHA-384 of the code's UTF-8 text, lower-case hex (the host's one hash, ``Canonical.sha384``)."""
    return hashlib.sha384(text.encode("utf-8")).hexdigest()


def _group_text(key) -> str:
    """A group's key as one text: a JSON list, so a value with a separator in it cannot be mistaken for two."""
    return json.dumps([None if v is None else (v.item() if hasattr(v, "item") else v) for v in key], separators=(",", ":"), ensure_ascii=False,
                      allow_nan=False)


def _number(v) -> float | None:
    if v is None:
        return None
    f = float(v)
    return f if math.isfinite(f) else None


class PythonEngine(Engine):
    kinds = OUTPUTS
    version = "1.0.0"

    def __init__(self):
        self._items = items.ItemsEngine()

    # ------------------------------------------------------------------------------------------------------------ one request
    def compute(self, req: Request, inputs: Inputs, progress: Progress) -> Result:
        p = req.params
        code = dict(p.get("code") or {})
        if req.kind not in OUTPUTS:
            raise ComputeError(f"the python engine answers {', '.join(OUTPUTS)}, not '{req.kind}'")
        name = str(code.get("name") or "")
        if not name or not isinstance(code.get("text"), str):
            raise ComputeError("the request carries no approved code item (name, version, hash, text)")
        want = str(code.get("hash") or "")
        if want and code_hash(code["text"]) != want:
            raise ComputeError(f"the text of {name} does not hash to its approved hash: it is not run")
        if req.kind == "check":
            return self._check(name, code, p)
        if req.kind == "function":
            return self._function(name, code, req, inputs, progress)
        return self._item(name, code, req, inputs, progress)

    # ------------------------------------------------------------------------------------------------------------ items
    def _item(self, name: str, code: dict, req: Request, inputs: Inputs, progress: Progress) -> Result:
        p = req.params
        interface = ITEM_KINDS[req.kind]
        have = code.get("interface")
        if have and have != interface:
            raise ComputeError(f"{name} is a {have} item, not a {interface}: ask the output {have}")
        manifest = {"name": name, "source": code["text"], "packages": list(code.get("packages") or []), "inputs": list(p.get("inputs") or []),
                    "output": p.get("format") or "echarts"}
        if code.get("entry"):
            manifest["entry"] = code["entry"]
        limits = dict(p.get("limits") or {})
        limits.setdefault("timeout", int(code.get("timeout_s") or 10))
        limits.setdefault("memory", int(code.get("memory_mb") or 256))
        inner = Request(id=req.id, kind="run", params={
            "interface": interface, "manifest": manifest, "params": dict(p.get("params") or {}), "frame": p.get("frame"),
            "frames": list(p.get("frames") or []), "names": list(p.get("names") or []), "extracts": dict(p.get("extracts") or {}),
            "contract": dict(p.get("contract") or {}), "group_by": p.get("group_by"), "limits": limits},
            user=req.user, roles=req.roles, view_signature=req.view_signature, cls=req.cls, seed=req.seed, scratch=req.scratch, chunk=req.chunk)
        done = self._items.compute(inner, inputs, progress)
        a = done.rows[0]
        if not a.get("ok"):
            return Result(rows=[{"error": {k: a.get(k) for k in ("kind", "error", "traceback")}}], meta=self._meta(code))
        shape = getattr(self, "_shape_" + req.kind)
        rows, meta = shape(name, code, a, req)
        meta.update(self._meta(code))
        meta["ms"] = a.get("ms")
        return Result(rows=rows, meta=meta)

    @staticmethod
    def _meta(code: dict) -> dict:
        return {"engine": "python", "code": {"name": code.get("name"), "version": code.get("version"), "hash": code.get("hash")}}

    def _shape_measure(self, name, code, a, req):
        by = a.get("by") or []
        rows = [{"ITEM": name, "GROUP": _group_text(r[:-1]), "VALUE": _number(r[-1]), "CODE": code.get("hash")} for r in a["rows"]]
        return rows, {"by": by, "vectorised": bool(a.get("vectorised"))}

    def _shape_column(self, name, code, a, req):
        numeric = a.get("type") in ("decimal", "number", "integer", "int")
        rows = []
        for i, v in enumerate(a["values"]):
            rows.append({"ITEM": name, "ROW": i, "VALUE": _number(v) if numeric else None, "TEXT": None if numeric or v is None else str(v)})
        return rows, {"type": a.get("type")}

    def _shape_transform(self, name, code, a, req):
        cells = None
        path = os.path.join(req.scratch, "out", items.RESULT_FILE)
        try:
            import pyarrow.parquet as pq  # noqa: WPS433
            cells = pq.read_table(path).to_pylist()
        except ImportError:
            cells = None
        if cells is None:                       # no pyarrow: the head only, and the answer says so
            names = [c["name"] for c in a["columns"]]
            cells = [dict(zip(names, r)) for r in a.get("head") or []]
        cap = int((req.params.get("limits") or {}).get("max_rows") or 100_000)
        rows = [{"ITEM": name, "ROW": i, "CELLS": json.dumps(_plain_row(c), separators=(",", ":"), ensure_ascii=False, allow_nan=False)}
                for i, c in enumerate(cells[:cap])]
        return rows, {"columns": a["columns"], "rows": a["rows"], "truncated": len(cells) < a["rows"]}

    def _shape_visual(self, name, code, a, req):
        kind = a["kind"]
        payload = a.get("spec") if "spec" in a else a.get("option") if "option" in a else a.get("svg") if "svg" in a else a.get("png")
        spec = payload if isinstance(payload, str) else json.dumps(payload, separators=(",", ":"), ensure_ascii=False, allow_nan=False)
        return [{"ITEM": name, "KIND": kind, "SPEC": spec}], {}

    def _shape_cell(self, name, code, a, req):
        table = a.get("table")
        return [{"ITEM": name, "STDOUT": a.get("stdout") or "",
                 "RESULT": None if table is None else json.dumps(table, separators=(",", ":"), ensure_ascii=False, allow_nan=False)}], {}

    # ------------------------------------------------------------------------------------------------------------ check
    def _check(self, name: str, code: dict, p: dict) -> Result:
        manifest = {"interface": code.get("interface") or "", "packages": list(code.get("packages") or []), "source": code["text"]}
        if code.get("entry"):
            manifest["entry"] = code["entry"]
        problems = items.check_source(manifest, [str(x) for x in p.get("allowed") or []])
        rows = [{"ITEM": name, "N": i, "LEVEL": q.get("level"), "CODE": q.get("code"), "PATH": q.get("path"), "MESSAGE": q.get("message")}
                for i, q in enumerate(problems)]
        return Result(rows=rows, meta=self._meta(code))

    # ------------------------------------------------------------------------------------------------------------ pivot functions
    def _function(self, name: str, code: dict, req: Request, inputs: Inputs, progress: Progress) -> Result:
        """A custom aggregate over a frame, per group: start, add each batch, merge the shards' states in order, finish.

        ``shards`` (default 1) cuts each group's rows into that many contiguous pieces, each added from its own start, then merged left to
        right: a mergeable function gives the same number as one pass (relative 1e-12); a holistic function is never merged and is asked
        with one shard. ``batch-rows`` is the size of a batch a function is fed.
        """
        p = req.params
        pivot_fn._alias()
        entry = code.get("entry") or ""
        if not entry:
            raise ComputeError(f"{name} names no class (entry) for the function")
        try:
            fn = pivot_fn.load(code["text"], entry, dict(p.get("params") or {}))
        except pivot_fn.FunctionError as e:
            return Result(rows=[{"error": {"kind": "invalid", "error": str(e), "traceback": ""}}], meta=self._meta(code))
        frame = p.get("frame")
        if not frame:
            raise ComputeError("a function is asked over one frame: name it in the parameter frame")
        table = inputs.table(frame)
        by = [str(c) for c in p.get("group_by") or []]
        names = list(fn.meta.get("inputs") or {}) or [c for c in table.names if c not in by]
        missing = [c for c in names + by if c not in table.names]
        if missing:
            raise ComputeError(f"the frame lacks the column(s) {missing} the function needs")
        shards = max(1, min(MAX_SHARDS, int(p.get("shards") or DEFAULT_SHARDS)))
        if fn.meta["kind"] == pivot_fn.Holistic:
            shards = 1
        size = max(1, int(p.get("batch-rows") or 65_536))
        groups: dict[tuple, list[int]] = {}
        keys = [table.column(c).to_list() for c in by]
        for r in range(len(table)):
            groups.setdefault(tuple(k[r] for k in keys), []).append(r)
        cols = {c: table.column(c).to_list() for c in names}
        rows = []
        try:
            for done, (key, idx) in enumerate(sorted(groups.items(), key=lambda kv: tuple("" if v is None else str(v) for v in kv[0])), 1):
                pieces = [idx[i * len(idx) // shards:(i + 1) * len(idx) // shards] for i in range(shards)]
                state = None
                for piece in pieces:
                    s = fn.start()
                    for at in range(0, max(len(piece), 1), size):
                        part = piece[at:at + size]
                        if not part:
                            continue
                        sub = arrow.Table([_column_of(table.column(c), [cols[c][i] for i in part]) for c in names])
                        s = fn.add(s, pivot_fn.Batch(sub, names))
                    state = s if state is None else fn.merge(state, s)
                rows.append({"ITEM": name, "GROUP": _group_text(key), "VALUE": fn.finish(state), "CODE": code.get("hash")})
                progress.fraction(done, len(groups))
        except pivot_fn.FunctionError as e:
            return Result(rows=[{"error": {"kind": "failed", "error": str(e), "traceback": ""}}], meta=self._meta(code))
        return Result(rows=rows, meta={**self._meta(code), "by": by, "kind": fn.meta["kind"], "shards": shards, "groups": len(groups)})


def _plain_row(row: dict) -> dict:
    out = {}
    for k, v in row.items():
        if hasattr(v, "isoformat"):
            v = v.isoformat()
        elif isinstance(v, float) and not math.isfinite(v):
            v = None
        elif isinstance(v, (bytes, bytearray)):
            v = v.hex()
        out[str(k)] = v
    return out


def _column_of(like: "arrow.Column", values: list) -> "arrow.Column":
    """A column of the same name and type as ``like`` holding ``values``."""
    t = like.type
    if t == arrow.INT64:
        return arrow.int64(like.name, values)
    if t == arrow.FLOAT64:
        return arrow.float64(like.name, values)
    if t == arrow.BOOL:
        return arrow.boolean(like.name, values)
    return arrow.utf8(like.name, values)

