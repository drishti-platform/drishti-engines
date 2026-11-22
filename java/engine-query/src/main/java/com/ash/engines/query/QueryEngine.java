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
package com.ash.engines.query;

import com.ash.engine.Control;
import com.ash.engine.Descriptor;
import com.ash.engine.Descriptors;
import com.ash.engine.Engine;
import com.ash.engine.EngineContext;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.EngineMeter;
import com.ash.engine.EngineStats;
import com.ash.engine.Health;
import com.ash.engine.Inputs;
import com.ash.engine.LifecycleState;
import com.ash.engine.Session;
import com.ash.engine.Snapshot;
import java.util.List;

/**
 * The query engine (EN-51): Drishti's pivot core and BI query engine as one engine, derived kinds its declarations. It holds no
 * connection, no thread and no executor of its own: grouping at the source is the host's grouped scan, and everything else runs on
 * the calling thread. So {@link #start} acquires nothing, {@link #suspend} and {@link #resume} only flip the state the host already
 * gates on, and {@link #shutdown} has nothing to release but the context (idempotent).
 *
 * <p>Sessions hold nothing between asks but the inputs' handle: the only input is open, so the host always runs this engine per
 * viewer (specification 8.6 rule 7) and every ask reads its tables as the caller.
 */
public final class QueryEngine implements Engine {

    static final String ID = "query";

    private volatile EngineContext context;
    private volatile EngineMeter meter = new EngineMeter(java.time.Clock.systemUTC());
    private volatile LifecycleState lifecycle = LifecycleState.NEW;
    private volatile Settings settings;

    /** The options, read once at {@link #init}. */
    record Settings(boolean exactPath, int maxCells, int maxHolisticCells, int batchRows, int explainRows, long workbookRows) {
        static Settings of(EngineContext c) {
            return new Settings(c.options().flag("exact-path"), (int) c.options().integer("max-cells"), (int) c.options().integer("max-holistic-cells"),
                    (int) c.options().integer("batch-rows"), (int) c.options().integer("explain-rows"), c.options().integer("workbook-rows"));
        }
    }

    @Override
    public Descriptor descriptor() {
        return Descriptors.of(QueryEngine.class, ID);
    }

    @Override
    public void init(EngineContext context) throws EngineException {
        try {
            this.settings = Settings.of(context);
        } catch (RuntimeException e) {
            lifecycle = LifecycleState.FAILED;
            throw new EngineException("QRY-1003", Kind.REFUSED, "the query engine's options do not read: " + e.getMessage());
        }
        this.context = context;
        this.meter = new EngineMeter(context.clock());
        this.lifecycle = LifecycleState.INITIALISED;
    }

    @Override
    public void start() {
        lifecycle = LifecycleState.RUNNING;                         // nothing to acquire: no connection, thread or cache of its own
    }

    @Override
    public void suspend() {
        lifecycle = LifecycleState.SUSPENDED;                       // state kept; the host has stopped admitting requests
    }

    @Override
    public void resume() {
        lifecycle = LifecycleState.RUNNING;
    }

    @Override
    public Session session(Snapshot snapshot, Inputs inputs, Control control) throws EngineException {
        if (lifecycle != LifecycleState.RUNNING) {
            throw new EngineException("ENG-5001", Kind.UNAVAILABLE, "the query engine is " + lifecycle.name().toLowerCase(java.util.Locale.ROOT)
                    + ": no new sessions");
        }
        return new QuerySession(this, context, meter, settings, snapshot, inputs);
    }

    @Override
    public List<String> notes() {
        Settings s = settings;
        return s == null ? List.of() : List.of(s.exactPath() ? "exact path: every figure from a scan, summed exactly" : "grouping pushed to the source when offered");
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
