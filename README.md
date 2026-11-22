<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Engines. Proprietary: see LICENSE. -->
# Drishti Engines

First-party platform engines (capacity, genomics workbench, indicators), one module and one JAR each, built on the
[drishti-engine](https://github.com/drishti-platform/drishti-engine) API and proven by its kit.

## Engine obligations

Every engine here implements, besides `descriptor`, `session` and `notes`:

- the five lifecycle methods `init`, `start`, `suspend`, `resume`, `shutdown` (specification 7.1);
- `Health health()`: UP, DEGRADED, DOWN or SUSPENDED, in agreement with the lifecycle state, reasons in words (7.4);
- `EngineStats stats()`: requests asked/answered/refused/failed/cancelled, latency p50/p95/p99, rows and bytes in and out, cache hits and
  misses, sessions open, queue depth, last activity, since start and a recent window (7.5). `EngineMeter` (engine-api) records them.

Both are cheap, never block and never do I/O. The kit's observability law proves them; an engine that fails it is not released. The same
holds for every engine in drishti-risk.
