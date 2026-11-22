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
package com.ash.engines.indicators;

import com.ash.engine.Control;
import com.ash.engine.Descriptor;
import com.ash.engine.Descriptors;
import com.ash.engine.Engine;
import com.ash.engine.EngineContext;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.EngineMeter;
import com.ash.engine.EngineStats;
import com.ash.engine.Filter;
import com.ash.engine.Health;
import com.ash.engine.InputTable;
import com.ash.engine.Inputs;
import com.ash.engine.LifecycleState;
import com.ash.engine.MemoryBudget;
import com.ash.engine.Scan;
import com.ash.engine.Session;
import com.ash.engine.Snapshot;
import java.util.ArrayList;
import java.util.List;

/**
 * Derived economic figures over series of observations, as an engine ({@code indicators}). A session reads the observations once; every
 * request is worked out within the economies its visibility lets through. The economy module computes no derived series today: this engine
 * is where they are born.
 */
public final class IndicatorsEngine implements Engine {

    static final String ID = "indicators";
    static final List<String> COLUMNS = List.of("ECONOMY", "INDICATOR", "YEAR", "VALUE", "UNIT");
    static final long ROW_BYTES = 120;

    private volatile EngineContext context;
    private volatile LifecycleState lifecycle = LifecycleState.NEW;
    private volatile EngineMeter meter = new EngineMeter(java.time.Clock.systemUTC());

    @Override
    public Descriptor descriptor() {
        return Descriptors.of(IndicatorsEngine.class, ID);
    }

    @Override
    public void init(EngineContext context) {
        this.context = context;
        this.meter = new EngineMeter(context.clock());
        this.lifecycle = LifecycleState.INITIALISED;
    }

    /** Nothing to start: no thread, timer, file or native library. */
    @Override
    public void start() {
        lifecycle = LifecycleState.RUNNING;
    }

    @Override
    public void suspend() {
        lifecycle = LifecycleState.SUSPENDED;
    }

    @Override
    public void resume() {
        lifecycle = LifecycleState.RUNNING;
    }

    @Override
    public Session session(Snapshot snapshot, Inputs inputs, Control control) throws EngineException {
        if (!lifecycle.serving()) {
            throw new EngineException("ENG-5020", Kind.REFUSED, "the indicators engine is " + (lifecycle == LifecycleState.SUSPENDED ? "suspended" : "not running") + "; no session opens");
        }
        InputTable t = inputs.table("observation");
        if (!t.present()) {
            return new IndicatorsSession(context, meter, descriptor(), snapshot, null, List.of(), null);
        }
        List<String> lacking = COLUMNS.stream().filter(c -> !t.columns().contains(c)).toList();
        if (!lacking.isEmpty()) {
            return new IndicatorsSession(context, meter, descriptor(), snapshot, null, lacking, null);
        }
        MemoryBudget.Reservation hold = context.memory().reserve(262_144 + ROW_BYTES * Math.max(t.rows(), 0), "observation");
        try {
            List<Object[]> rows = new ArrayList<>();
            t.scan(new Scan(COLUMNS, Filter.ALL, 4_096), b -> {
                control.check();
                String[] economy = b.texts("ECONOMY");
                String[] indicator = b.texts("INDICATOR");
                long[] year = b.integers("YEAR");
                double[] value = b.numbers("VALUE");
                String[] unit = b.texts("UNIT");
                for (int i = 0; i < b.rows(); i++) {
                    if (economy[i] == null || indicator[i] == null || b.isNull("YEAR", i) || b.isNull("VALUE", i) || Double.isNaN(value[i])) {
                        continue;
                    }
                    rows.add(new Object[] {economy[i], indicator[i], (int) year[i], value[i], unit[i] == null ? "" : unit[i]});
                }
            });
            return new IndicatorsSession(context, meter, descriptor(), snapshot, rows, List.of(), hold);
        } catch (EngineException | RuntimeException e) {
            hold.close();
            throw e;
        }
    }

    @Override
    public Health health() {
        return Health.of(lifecycle, meter.snapshot().takenAt());
    }

    @Override
    public EngineStats stats() {
        return meter.snapshot();
    }

    @Override
    public void shutdown() {
        lifecycle = LifecycleState.SHUTDOWN;
        context = null;
    }
}
