/*
 * Drishti Engines · first-party engines by contract.
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
package com.ash.engines.python;

import com.ash.engine.Control;
import com.ash.engine.Descriptor;
import com.ash.engine.Engine;
import com.ash.engine.EngineContext;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.InputTable;
import com.ash.engine.Inputs;
import com.ash.engine.MemoryBudget;
import com.ash.engine.Missing;
import com.ash.engine.Session;
import com.ash.engine.Snapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The host's process binding of the {@code python} engine (specification 14.1): the descriptor names a worker, not a Java class, and a
 * host that runs it creates one of these. It is not inside the engine JAR (a JAR's scan forbids starting a process); it is host-side
 * code, in the pool's own JAR. The binding owns nothing but its sessions: the pool is either shared (the host's one pool, so the
 * pack engines written in Python use the same workers) or owned (made at {@link #open} from the engine's options and stopped at
 * {@link #close}, for the kit, the command line and tests).
 */
public final class PythonBinding implements Engine {

    private final Descriptor descriptor;
    private final ClassLoader resources;
    private final String node;
    private volatile PythonPool pool;
    private final boolean owns;
    private volatile EngineContext context;

    private PythonBinding(Descriptor descriptor, PythonPool shared, String node, ClassLoader resources) {
        this.descriptor = descriptor;
        this.pool = shared;
        this.owns = shared == null;
        this.node = node;
        this.resources = resources;
    }

    /** A binding on the host's shared pool (which the host started and stops). */
    public static PythonBinding sharing(Descriptor descriptor, PythonPool pool) {
        return new PythonBinding(descriptor, pool, "", null);
    }

    /**
     * A binding that makes its own pool at open from the engine's options, in the instance's work folder, and stops it at close.
     *
     * @param resources the loader the engine JAR's worker package is read from
     */
    public static PythonBinding owning(Descriptor descriptor, String node, ClassLoader resources) {
        return new PythonBinding(descriptor, null, node, resources);
    }

    @Override
    public Descriptor descriptor() {
        return descriptor;
    }

    /** Lifecycle 1: the host gives the context; options are checked and the pool is made, not yet started. */
    public void init(EngineContext ctx) throws EngineException {
        this.context = ctx;
        if (descriptor.worker() == null || !descriptor.worker().startsWith("python:")) {
            throw new EngineException(Codes.REQUEST, Kind.REFUSED, "the descriptor of " + descriptor.id() + " names no python worker");
        }
        if (!owns) {
            return;
        }
        PoolSettings s;
        try {
            s = PoolSettings.of(ctx.options());
        } catch (IllegalArgumentException e) {
            throw new EngineException(Codes.REQUEST, Kind.REFUSED, "the options of " + descriptor.id() + " do not make a pool: " + e.getMessage());
        }
        String entry = descriptor.worker().substring("python:".length());
        PythonPool p = new PythonPool(s, ctx.workDir(), node.isEmpty() ? "node" : node, List.of(entry), resources);
        try {
            probe(s);
        } catch (PoolException e) {
            p.close();
            throw e.toEngine();
        }
        this.pool = p;
    }

    /** Lifecycle 2: the pool is up (the worker package extracted, the warm workers started). */
    public void start() throws EngineException {
        PythonPool p = pool;
        if (p == null) {
            throw new EngineException("ENG-5020", Kind.REFUSED, "the python engine is not initialised");
        }
        if (!owns) {
            return;
        }
        try {
            p.start();
        } catch (PoolException e) {
            p.close();
            throw e.toEngine();
        }
    }

    /** Lifecycle 3: no new work; requests in flight finish; workers stay. */
    public void suspend() {
        PythonPool p = pool;
        if (p != null) {
            p.suspend();
        }
    }

    /** Lifecycle 4: cheap; the workers were kept. */
    public void resume() {
        PythonPool p = pool;
        if (p != null) {
            p.resume();
        }
    }

    /** Lifecycle 5: idempotent; no worker process is left (an owned pool is stopped; a shared pool is the host's). */
    public void shutdown() {
        close();
    }

    /** Until the host calls the five lifecycle methods: init then start. */
    @Override
    public void open(EngineContext ctx) throws EngineException {
        init(ctx);
        start();
    }

    /** Python 3.10 or later must be there, and strict isolation only where the platform has it; said in words before any request. */
    static void probe(PoolSettings s) {
        try {
            Process pr = new ProcessBuilder(s.command(), "-s", "-B", "-c", "import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 3)")
                    .redirectErrorStream(true).start();
            if (!pr.waitFor(20, TimeUnit.SECONDS)) {
                pr.destroyForcibly();
                throw new PoolException(PoolException.Code.UNAVAILABLE, "'" + s.command() + "' did not answer within 20 s");
            }
            if (pr.exitValue() != 0) {
                throw new PoolException(PoolException.Code.UNAVAILABLE, "'" + s.command() + "' is not Python 3.10 or later; set the option interpreter");
            }
        } catch (java.io.IOException e) {
            throw new PoolException(PoolException.Code.UNAVAILABLE, "Python was not found ('" + s.command() + "'): set the option interpreter to a Python 3.10 or later");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PoolException(PoolException.Code.CANCELLED, "interrupted while looking for Python");
        }
        if (s.strict() && !System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            throw new PoolException(PoolException.Code.UNAVAILABLE, "the strict sandbox (no network, files held by the kernel) is Linux only; "
                    + "set the option isolation to standard for development");
        }
    }

    @Override
    public Session session(Snapshot snapshot, Inputs inputs, Control control) throws EngineException {
        PythonPool p = pool;
        if (p == null) {
            throw new EngineException("ENG-5020", Kind.REFUSED, "the python engine is not open");
        }
        InputTable t = inputs.table("code");
        if (!t.present()) {
            return new PythonSession(context, descriptor, p, snapshot, inputs, null, List.of(), null);
        }
        List<String> lacking = new ArrayList<>();
        for (String c : ApprovedCode.REQUIRED) {
            if (!t.columns().contains(c)) {
                lacking.add(c);
            }
        }
        if (!lacking.isEmpty()) {
            return new PythonSession(context, descriptor, p, snapshot, inputs, null, lacking, null);
        }
        MemoryBudget.Reservation hold = context.memory().reserve(65_536 + 4_096L * Math.max(t.rows(), 0), "code");
        try {
            return new PythonSession(context, descriptor, p, snapshot, inputs, ApprovedCode.read(t, control), List.of(), hold);
        } catch (EngineException | RuntimeException e) {
            hold.close();
            throw e;
        }
    }

    @Override
    public List<String> notes() {
        PythonPool p = pool;
        return p == null ? List.of("python pool: not started") : p.notes();
    }

    @Override
    public void close() {
        PythonPool p = pool;
        pool = owns ? null : p;
        if (owns && p != null) {
            p.close();
        }
        context = null;
    }

    /** A missing input of the code table, in words (for hosts that show it before any ask). */
    static Missing missingCode(Snapshot snapshot, List<String> columns, List<String> outputs) {
        return new Missing("code", "platform/code-item@1", snapshot, columns, outputs);
    }

    /** The pool's time budget, for a host's own timers. */
    Duration budget() {
        return pool == null ? Duration.ZERO : pool.settings().timeout();
    }
}
