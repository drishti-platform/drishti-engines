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
package com.ash.engines.capacity;

import com.ash.engine.Control;
import com.ash.engine.Descriptor;
import com.ash.engine.Descriptors;
import com.ash.engine.Engine;
import com.ash.engine.EngineMeter;
import com.ash.engine.EngineStats;
import com.ash.engine.Health;
import com.ash.engine.LifecycleState;
import com.ash.engine.EngineContext;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.Inputs;
import com.ash.engine.Session;
import com.ash.engine.Snapshot;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The capacity planner as an engine with no inputs (a calculator over parameters). The planner's one file, {@code capacity/planner.yaml}
 * of this JAR, is read at {@link #open}; every input of it is a request parameter; one {@link Evaluator} works the figures out; the
 * explanation and the workbook are written from the same expression trees. The browser's, the command line's and the workbook's own
 * evaluators of the same file are not needed once a host runs this engine.
 */
public final class CapacityEngine implements Engine {

    static final String ID = "capacity";

    private volatile EngineContext context;
    private volatile Planner planner;
    private volatile LifecycleState lifecycle = LifecycleState.NEW;
    private volatile EngineMeter meter = new EngineMeter(java.time.Clock.systemUTC());

    @Override
    public Descriptor descriptor() {
        return Descriptors.of(CapacityEngine.class, ID);
    }

    @Override
    public void init(EngineContext context) throws EngineException {
        Planner p = Planner.builtIn(CapacityEngine.class);
        Set<String> declared = new LinkedHashSet<>(descriptor().params().keySet());
        Set<String> wanted = new LinkedHashSet<>();
        p.inputs().forEach(i -> wanted.add(i.param()));
        if (!declared.equals(wanted)) {
            throw new EngineException("ENG-1003", Kind.REFUSED,
                    "the descriptor's parameters and the planner's inputs differ; run tools/capacity_descriptor.py in the drishti-engines repository");
        }
        this.context = context;
        this.meter = new EngineMeter(context.clock());
        this.planner = p;
        this.lifecycle = LifecycleState.INITIALISED;
    }

    /** Nothing to start: the planner is read at init and the engine holds no thread, timer or connection. */
    @Override
    public void start() {
        lifecycle = LifecycleState.RUNNING;
    }

    /** While suspended no new session opens; asks of open sessions still answer (they hold nothing that changes). */
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
            throw new EngineException("ENG-5020", Kind.REFUSED, "the capacity engine is " + (lifecycle == LifecycleState.SUSPENDED ? "suspended" : "not running") + "; no session opens");
        }
        return new CapacitySession(context, meter, planner, descriptor(), snapshot);
    }

    @Override
    public List<String> notes() {
        Planner p = planner;
        return p == null ? List.of() : List.of(p.inputs().size() + " inputs, " + p.figures().size() + " figures");
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
        planner = null;
    }
}
