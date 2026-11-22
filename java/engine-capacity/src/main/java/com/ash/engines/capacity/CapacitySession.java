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

import com.ash.engine.Answer;
import com.ash.engine.Answers;
import com.ash.engine.Cell;
import com.ash.engine.Control;
import com.ash.engine.Descriptor;
import com.ash.engine.EngineContext;
import com.ash.engine.EngineMeter;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.Explaining;
import com.ash.engine.Explanation;
import com.ash.engine.Request;
import com.ash.engine.Session;
import com.ash.engine.Snapshot;
import com.ash.engine.Table;
import com.ash.engine.Tables;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A capacity session: nothing is loaded (the engine has no inputs), so every ask is the planner worked out over the request's parameters. */
final class CapacitySession implements Session, Explaining {

    private final EngineContext context;
    private final EngineMeter meter;
    private final Planner planner;
    private final Descriptor descriptor;
    private final Snapshot snapshot;
    private volatile boolean closed;

    CapacitySession(EngineContext context, EngineMeter meter, Planner planner, Descriptor descriptor, Snapshot snapshot) {
        this.context = context;
        this.meter = meter;
        meter.sessionOpened();
        this.planner = planner;
        this.descriptor = descriptor;
        this.snapshot = snapshot;
    }

    @Override
    public Snapshot snapshot() {
        return snapshot;
    }

    @Override
    public long memoryBytes() {
        return 0;
    }

    /** The inputs the request gives, by the planner's names; the rest stay at their defaults. */
    private Map<String, Object> overrides(Request request) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Planner.Input i : planner.inputs()) {
            Object v = request.param(i.param());
            if (v != null) {
                m.put(i.name(), v instanceof Number n ? (Object) n.doubleValue() : v.toString());
            }
        }
        return m;
    }

    @Override
    public Answer ask(Request request, Control control) throws EngineException {
        return meter.measure(() -> compute(request, control), 0, 0);
    }

    private Answer compute(Request request, Control control) throws EngineException {
        Evaluator ev = new Evaluator(planner.cells(overrides(request)));
        List<Table> tables = new ArrayList<>();
        for (String output : request.outputs()) {
            control.check();
            tables.add(switch (output) {
                case "figures" -> figures(ev);
                case "lines" -> lines(ev);
                default -> catalogue();
            });
        }
        return Answers.complete(context, this, request, tables);
    }

    private Table figures(Evaluator ev) throws EngineException {
        Tables.Builder out = Tables.of(descriptor.schema("figures"));
        for (Planner.Figure f : planner.figures()) {
            if (!f.text()) {
                out.row((long) f.ordinal(), f.section(), f.name(), number(f, ev));
            }
        }
        return out.build();
    }

    /** A figure's number; a text or a value that is not a number (a division by zero) is refused in words. */
    static double number(Planner.Figure f, Evaluator ev) throws EngineException {
        Object v;
        try {
            v = ev.value(f.name());
        } catch (Evaluator.Failure e) {
            throw new EngineException("ENG-3002", Kind.REFUSED, "the figure " + f.name() + " cannot be worked out: " + e.getMessage());
        }
        if (!(v instanceof Double d) || Double.isNaN(d) || Double.isInfinite(d)) {
            throw new EngineException("ENG-3002", Kind.REFUSED, "the parameters make " + f.name() + " " + (v instanceof Double ? "not a finite number (a division by zero)" : "text, not a number"));
        }
        return d;
    }

    private Table lines(Evaluator ev) throws EngineException {
        Tables.Builder out = Tables.of(descriptor.schema("lines"));
        for (Planner.Figure f : planner.figures()) {
            if (f.text()) {
                try {
                    out.row((long) f.ordinal(), f.section(), f.name(), Evaluator.text(ev.value(f.name())));
                } catch (Evaluator.Failure e) {
                    throw new EngineException("ENG-3002", Kind.REFUSED, "the line " + f.name() + " cannot be worked out: " + e.getMessage());
                }
            }
        }
        return out.build();
    }

    private Table catalogue() {
        Tables.Builder out = Tables.of(descriptor.schema("catalogue"));
        for (Planner.Figure f : planner.figures()) {
            out.row((long) f.ordinal(), f.section(), f.name(), f.label(), f.unit(), f.why(), f.expression());
        }
        return out.build();
    }

    @Override
    public Explanation explain(Request request, Cell cell, Control control) throws EngineException {
        Planner.Figure f = planner.figure(cell.key().get("NAME"));
        if (f == null || f.text() || !"figures".equals(cell.output()) || !"VALUE".equals(cell.column())) {
            throw new EngineException("ENG-3005", Kind.REFUSED, "only VALUE of figures, for a figure of the plan, is explained");
        }
        return CapacityExplain.of(planner, f, overrides(request), cell);
    }

    @Override
    public void workbook(Request request, OutputStream out, Control control) throws EngineException, IOException {
        Evaluator ev = new Evaluator(planner.cells(overrides(request)));
        CapacityBook.write(out, planner, overrides(request), ev);
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            meter.sessionClosed();
        }
    }

    boolean closed() {
        return closed;
    }
}
