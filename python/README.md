---
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Proprietary: see LICENSE.
name: python
summary: The platform python engine (EN-54): one engine on one process pool for every approved Python (measures, columns, transforms, visuals, notebook cells, custom aggregates); replaces Drishti's python, pivot-fn and reactive pools.
status: 0.1.0-SNAPSHOT, unbuilt (front-load for combined i8)
modules: [engine (descriptor + worker package, no Java class), pool (host-side pool manager and binding), worker (Python package engine_python, tests)]
lifecycle: init(EngineContext), start() pool up, suspend() no new work, resume() cheap, shutdown() idempotent, no process left
---

# python engine

* Approved code is an INPUT (`code`, contract `platform/code-item@1`: name, version, hash, text, interface, entry, packages); only approved
  code is a row, the text is held to its SHA-384, the hash is in every row (`CODE`) and note. Frames are an OPEN input read as the caller.
* Outputs: `measure`, `column`, `transform`, `visual`, `cell`, `function` (start/add/merge/finish, shards proven equal to one pass), `check`.
* Explain: one `opaque` step per measure cell naming the code and its hash and the workbook cell holding the value (counted by the kit).
* Pool: fair queue by class and user, batch share, back-pressure `ENG-5001`, wall time, cancel (fork: the child only), memory/CPU limits,
  strict sandbox (no network, Landlock files; Linux), crash is `ENG-7001` and the next request gets a fresh worker.
* Hand-over: Arrow IPC files in a per-request private scratch folder (worker has its own pure-Python codec).
* Kit cases recorded from Drishti's tests: `engine/tck/cases/python/items.yaml`; worker tests in `worker/tests`.
* Not built or run yet (a drill owns the machine). Because a JAR's kit scan forbids starting processes, the pool lives in `pool/`, not in the engine JAR.
