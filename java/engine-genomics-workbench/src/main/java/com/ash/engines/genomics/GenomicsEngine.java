/*
 * Drishti Engines · the first-party platform engines.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.engines.genomics;

import com.ash.engine.Control;
import com.ash.engine.Descriptor;
import com.ash.engine.Descriptors;
import com.ash.engine.Engine;
import com.ash.engine.EngineContext;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.Filter;
import com.ash.engine.InputTable;
import com.ash.engine.Inputs;
import com.ash.engine.MemoryBudget;
import com.ash.engine.Scan;
import com.ash.engine.Session;
import com.ash.engine.Snapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The genomics showcase's computations as an engine ({@code genomics-workbench}). A session reads the rows of the study, sample, variant,
 * call, copy-number, protein and design contracts once; every request is computed within the rows its visibility lets through (by sample),
 * so one session serves every viewer. A table that is not there is an answer (NOT_FOUND with its name), never an error.
 */
public final class GenomicsEngine implements Engine {

    static final String ID = "genomics-workbench";

    private volatile EngineContext context;
    private volatile boolean running;
    private volatile boolean suspended;

    @Override
    public Descriptor descriptor() {
        return Descriptors.of(GenomicsEngine.class, ID);
    }

    @Override
    public void init(EngineContext context) {
        this.context = context;
    }

    /** Nothing to start: the engine holds no thread, timer or connection; sessions read their rows when they open. */
    @Override
    public void start() {
        running = true;
        suspended = false;
    }

    /** While suspended no new session opens; open sessions still answer from the rows they hold. */
    @Override
    public void suspend() {
        suspended = true;
    }

    @Override
    public void resume() {
        suspended = false;
    }

    @Override
    public Session session(Snapshot snapshot, Inputs inputs, Control control) throws EngineException {
        if (!running || suspended) {
            throw new EngineException("ENG-5020", Kind.REFUSED, "the genomics workbench is " + (suspended ? "suspended" : "not started") + "; no session opens");
        }
        Descriptor d = descriptor();
        Map<String, List<V.Row>> tables = new LinkedHashMap<>();
        Map<String, List<String>> lacking = new LinkedHashMap<>();
        long bytes = 0;
        List<MemoryBudget.Reservation> holds = new ArrayList<>();
        try {
            for (Map.Entry<String, Descriptor.Input> e : d.inputs().entrySet()) {
                InputTable t = inputs.table(e.getKey());
                List<String> cols = e.getValue().columns();
                if (!t.present()) {
                    lacking.put(e.getKey(), List.of());
                    continue;
                }
                List<String> missing = cols.stream().filter(c -> !t.columns().contains(c)).toList();
                if (!missing.isEmpty()) {
                    lacking.put(e.getKey(), missing);
                    continue;
                }
                long per = d.limits().perRowBytes().getOrDefault(e.getKey(), 200L);
                holds.add(context.memory().reserve(Math.max(t.rows(), 0) * per + 4_096, e.getKey()));
                List<V.Row> rows = new ArrayList<>();
                t.scan(new Scan(cols, Filter.ALL, 4_096), b -> {
                    control.check();
                    rows.addAll(V.rows(b, cols));
                });
                tables.put(e.getKey(), rows);
                bytes += rows.size() * per;
            }
        } catch (EngineException | RuntimeException ex) {
            holds.forEach(MemoryBudget.Reservation::close);
            throw ex;
        }
        return new GenomicsSession(context, d, snapshot, tables, lacking, bytes, holds);
    }

    @Override
    public void shutdown() {
        running = false;
        suspended = false;
        context = null;
    }
}
