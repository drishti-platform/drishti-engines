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

import com.ash.engine.Answer;
import com.ash.engine.Answers;
import com.ash.engine.Cell;
import com.ash.engine.Control;
import com.ash.engine.Descriptor;
import com.ash.engine.EngineContext;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.EngineMeter;
import com.ash.engine.Explaining;
import com.ash.engine.Explanation;
import com.ash.engine.Filter;
import com.ash.engine.MemoryBudget;
import com.ash.engine.Missing;
import com.ash.engine.Request;
import com.ash.engine.Session;
import com.ash.engine.Snapshot;
import com.ash.engine.Table;
import com.ash.engine.Tables;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** An indicators session: the observations of one snapshot, held; every ask is worked out within the economies the request may see. */
final class IndicatorsSession implements Session, Explaining {

    private final EngineContext context;
    private final EngineMeter meter;
    private final Descriptor descriptor;
    private final Snapshot snapshot;
    private final List<Object[]> rows;
    private final List<String> lacking;
    private final MemoryBudget.Reservation hold;
    private volatile boolean closed;

    IndicatorsSession(EngineContext context, EngineMeter meter, Descriptor descriptor, Snapshot snapshot, List<Object[]> rows, List<String> lacking,
            MemoryBudget.Reservation hold) {
        this.context = context;
        this.meter = meter;
        this.descriptor = descriptor;
        this.snapshot = snapshot;
        this.rows = rows;
        this.lacking = List.copyOf(lacking);
        this.hold = hold;
        meter.sessionOpened();
    }

    @Override
    public Snapshot snapshot() {
        return snapshot;
    }

    @Override
    public long memoryBytes() {
        return closed || rows == null ? 0 : 262_144 + IndicatorsEngine.ROW_BYTES * rows.size();
    }

    /** What a request worked out: the series it may see, their figures and the ranks. */
    record Worked(int years, List<Calc.Figures> figures, List<Calc.Rank> ranks) {
    }

    private Worked work(Request request) throws EngineException {
        Filter see = request.visibility() == null ? null : request.visibility().rows().get("observation");
        if (see == null) {
            throw new EngineException("ENG-4002", Kind.NOT_PERMITTED, "the request names no rows of observation it may read");
        }
        String only = request.param("indicator") == null ? "" : request.param("indicator").toString();
        int years = request.param("years") instanceof Number y ? y.intValue() : 1;       // ranks does not take years
        List<Object[]> seen = rows.stream().filter(r -> (only.isEmpty() || only.equals(r[1])) && see.test(c -> "ECONOMY".equals(c) ? (String) r[0] : null))
                .collect(Collectors.toList());
        List<Calc.Series> series = Calc.series(seen);
        List<Calc.Figures> figures = series.stream().map(s -> Calc.figures(s, years)).toList();
        return new Worked(years, figures, Calc.ranks(series));
    }

    @Override
    public Answer ask(Request request, Control control) throws EngineException {
        return meter.measure(() -> compute(request, control), rows == null ? 0 : rows.size(), 0);
    }

    private Answer compute(Request request, Control control) throws EngineException {
        if (rows == null) {
            return Answers.notFound(context, this, request, List.of(new Missing("observation", "economy/observation@1", snapshot, lacking, request.outputs())));
        }
        Worked w = work(request);
        control.check();
        List<Table> out = new ArrayList<>();
        for (String o : request.outputs()) {
            out.add(table(o, w));
        }
        return Answers.complete(context, this, request, out);
    }

    Table table(String output, Worked w) {
        Tables.Builder b = Tables.of(descriptor.schema(output));
        if (output.equals("series")) {
            for (Calc.Figures f : w.figures()) {
                Calc.Series s = f.series();
                b.row(s.economy(), s.indicator(), s.unit(), (long) s.latestYear(), (long) s.n(), s.latest(), f.previous(), f.change(), f.pct(), f.cagr(), f.mean(), f.stdev(), f.z());
            }
        } else {
            for (Calc.Rank r : w.ranks()) {
                b.row(r.indicator(), r.economy(), (long) r.rank(), (long) r.of(), r.latest(), r.percentile());
            }
        }
        return b.build();
    }

    @Override
    public Explanation explain(Request request, Cell cell, Control control) throws EngineException {
        if (rows == null) {
            throw new EngineException("ENG-3005", Kind.REFUSED, "there are no observations for this snapshot; nothing is explained");
        }
        return IndicatorsExplain.of(work(request), cell);
    }

    @Override
    public void workbook(Request request, OutputStream out, Control control) throws EngineException, IOException {
        Worked w = rows == null ? new Worked(request.param("years") instanceof Number y ? y.intValue() : 1, List.of(), List.of()) : work(request);
        IndicatorsBook.write(out, w, request.outputs(), o -> table(o, w));
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            meter.sessionClosed();
            if (hold != null) {
                hold.close();
            }
        }
    }
}
