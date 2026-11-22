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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A genomics-workbench session: the rows of the study read once and held; every ask is computed within the samples the request's
 * visibility lets through. Holds nothing of a viewer.
 */
final class GenomicsSession implements Session, Explaining {

    private final EngineContext context;
    private final EngineMeter meter;
    private final Descriptor descriptor;
    private final Snapshot snapshot;
    private final Map<String, List<V.Row>> tables;
    private final Map<String, List<String>> lacking;
    private final long bytes;
    private final List<MemoryBudget.Reservation> holds;
    private volatile boolean closed;

    GenomicsSession(EngineContext context, EngineMeter meter, Descriptor descriptor, Snapshot snapshot, Map<String, List<V.Row>> tables, Map<String, List<String>> lacking,
            long bytes, List<MemoryBudget.Reservation> holds) {
        this.context = context;
        this.meter = meter;
        meter.sessionOpened();
        this.descriptor = descriptor;
        this.snapshot = snapshot;
        this.tables = tables;
        this.lacking = lacking;
        this.bytes = bytes;
        this.holds = holds;
    }

    @Override
    public Snapshot snapshot() {
        return snapshot;
    }

    @Override
    public long memoryBytes() {
        return closed ? 0 : bytes;
    }

    // ---------------------------------------------------------------------------------------------------- what a request computes

    /** What one request worked out, kept so the tables, the explanations and the workbook come from the same figures. */
    record Worked(Cohort.Summary summary, Cohort.Matrix matrix, String protein, List<Designs.Round> rounds, List<Designs.Entry> board, String gene) {
    }

    /** The needs of an output that are not there: one {@link Missing} per input. */
    private List<Missing> missing(List<String> outputs) {
        Map<String, List<String>> byInput = new LinkedHashMap<>();
        for (String o : outputs) {
            for (String in : descriptor.needs(o)) {
                if (lacking.containsKey(in)) {
                    byInput.computeIfAbsent(in, k -> new ArrayList<>()).add(o);
                }
            }
        }
        List<Missing> out = new ArrayList<>();
        byInput.forEach((in, os) -> out.add(new Missing(in, descriptor.inputs().get(in).contract(), snapshot, lacking.get(in), os)));
        return out;
    }

    private static Filter see(Request request, String input) throws EngineException {
        Filter f = request.visibility() == null ? null : request.visibility().rows().get(input);
        if (f == null) {
            throw new EngineException("ENG-4002", Kind.NOT_PERMITTED, "the request names no rows of " + input + " it may read");
        }
        return f;
    }

    /** The rows of a table the request may see (by the sample they are about); null when the input is not there. */
    private List<V.Row> visible(Request request, String input, boolean scoped) throws EngineException {
        List<V.Row> rows = tables.get(input);
        if (rows == null) {
            return null;
        }
        if (!scoped) {
            return rows;
        }
        Filter f = see(request, input);
        if (f instanceof Filter.All) {
            return rows;
        }
        return rows.stream().filter(r -> f.test(c -> "SAMPLE_ID".equals(c) ? V.str(r.get("SAMPLE_ID")) : null)).collect(Collectors.toList());
    }

    private String text(Request request, String param) {
        Object v = request.param(param);
        return v == null ? "" : v.toString();
    }

    private Sel selection(Request request) throws EngineException {
        try {
            return Sel.of(text(request, "cohort"), text(request, "cluster"), text(request, "box"), text(request, "samples"));
        } catch (IllegalArgumentException e) {
            throw new EngineException("ENG-3002", Kind.REFUSED, e.getMessage());
        }
    }

    private Cohort cohort(Request request) throws EngineException {
        return new Cohort(visible(request, "study-sample", true), visible(request, "variant-call", true), visible(request, "study-variant", false),
                visible(request, "study-gene", false), visible(request, "cnv-segment", true));
    }

    private V.Row proteinOf(Request request) throws EngineException {
        String id = text(request, "protein");
        V.Row p = Designs.protein(tables.get("protein"), id);
        if (p == null) {
            String known = tables.get("protein").stream().map(x -> V.str(x.get("PROTEIN")) + " (" + V.str(x.get("GENE")) + ")").collect(Collectors.joining(", "));
            throw new EngineException("ENG-3002", Kind.REFUSED, "no protein " + (id.isEmpty() ? "named" : id) + " (there are " + known + ")");
        }
        return p;
    }

    /** Works out the outputs whose inputs are there. */
    Worked work(Request request, List<String> outputs) throws EngineException {
        Cohort.Summary summary = null;
        Cohort.Matrix matrix = null;
        List<Designs.Round> rounds = null;
        List<Designs.Entry> board = null;
        String protein = null;
        Cohort c = null;
        for (String o : outputs) {
            if (descriptor.needs(o).stream().anyMatch(lacking::containsKey)) {
                continue;
            }
            switch (o) {
                case "summary" -> {
                    c = c == null ? cohort(request) : c;
                    summary = c.summary(selection(request), text(request, "gene"));
                }
                case "matrix-cells", "matrix-genes" -> {
                    c = c == null ? cohort(request) : c;
                    matrix = matrix != null ? matrix : c.matrix(selection(request), ((Number) request.param("genes-wanted")).intValue(), text(request, "gene"));
                }
                case "rounds" -> {
                    protein = V.str(proteinOf(request).get("PROTEIN"));
                    rounds = Designs.rounds(tables.get("design"), tables.get("design-round"), protein);
                }
                case "leaderboard" -> {
                    protein = V.str(proteinOf(request).get("PROTEIN"));
                    board = Designs.leaderboard(tables.get("design"), tables.get("design-mutation"), protein, ((Number) request.param("round")).intValue(),
                            ((Number) request.param("n")).intValue());
                }
                default -> {
                }
            }
        }
        return new Worked(summary, matrix, protein, rounds, board, text(request, "gene"));
    }

    @Override
    public Answer ask(Request request, Control control) throws EngineException {
        long rowsIn = tables.values().stream().mapToLong(List::size).sum();
        return meter.measure(() -> compute(request, control), rowsIn, 0);
    }

    private Answer compute(Request request, Control control) throws EngineException {
        List<Missing> missing = missing(request.outputs());
        Worked w = work(request, request.outputs());
        control.check();
        List<Table> out = new ArrayList<>();
        for (String o : request.outputs()) {
            if (descriptor.needs(o).stream().anyMatch(lacking::containsKey)) {
                continue;
            }
            out.add(table(o, w));
        }
        return Answers.of(context, this, request, out, missing);
    }

    Table table(String output, Worked w) {
        Tables.Builder b = Tables.of(descriptor.schema(output));
        switch (output) {
            case "summary" -> {
                Cohort.Summary s = w.summary();
                Double rate = s.samples() == 0 ? null : rate(s.responders(), s.samples());
                Integer alt = s.altered();
                b.row(s.cohort(), s.words(), (long) s.cohortSamples(), (long) s.samples(), (long) s.responders(), rate, alt == null ? null : (Object) (long) alt,
                        alt == null || s.samples() == 0 ? null : rate(alt, s.samples()), alt == null || alt == 0 ? null : rate(s.respondersAltered(), alt),
                        alt == null || s.samples() - alt == 0 ? null : rate(s.respondersNot(), s.samples() - alt));
            }
            case "matrix-cells" -> {
                long i = 0;
                for (Cohort.Cell c : w.matrix().cells()) {
                    b.row(++i, c.sample(), c.gene(), c.alteration());
                }
            }
            case "matrix-genes" -> {
                long i = 0;
                for (Cohort.GeneCount g : w.matrix().counts()) {
                    b.row(++i, g.gene(), (long) g.samples(), w.matrix().selected() == 0 ? 0.0 : rate(g.samples(), w.matrix().selected()));
                }
            }
            case "rounds" -> {
                for (Designs.Round r : w.rounds()) {
                    double[] v = r.sorted();
                    b.row(w.protein(), (long) r.round(), V.round(Designs.q(v, 0.05), 3), V.round(Designs.q(v, 0.25), 3), V.round(Designs.q(v, 0.5), 3),
                            V.round(Designs.q(v, 0.75), 3), V.round(Designs.q(v, 0.95), 3), (long) v.length, V.round(v[v.length - 1], 3), r.method());
                }
            }
            default -> {
                for (Designs.Entry e : w.board()) {
                    V.Row d = e.design();
                    double tm = V.num(d.get("DELTA_TM"));
                    double ms = V.num(d.get("MODEL_SCORE"));
                    double nc = V.num(d.get("N_CHANGES"));
                    b.row((long) e.rank(), V.str(d.get("DESIGN_ID")), (long) V.integer(d.get("ROUND"), 0), V.num(d.get("ACTIVITY")), Double.isNaN(tm) ? null : (Object) tm,
                            Double.isNaN(nc) ? null : (Object) (long) Math.round(nc), Double.isNaN(ms) ? null : (Object) ms, V.str(d.get("STATUS")), V.str(d.get("PARENT")), e.changes());
                }
            }
        }
        return b.build();
    }

    /** A share of a whole, to four decimals, as the showcase rounds it. */
    static double rate(int part, int whole) {
        return V.round(part / (double) whole, 4);
    }

    // ---------------------------------------------------------------------------------------------------- explain and workbook

    @Override
    public Explanation explain(Request request, Cell cell, Control control) throws EngineException {
        Worked w = work(request, List.of(cell.output()));
        return GenomicsExplain.of(w, cell);
    }

    @Override
    public void workbook(Request request, OutputStream out, Control control) throws EngineException, IOException {
        Worked w = work(request, request.outputs());
        GenomicsBook.write(out, w, request.outputs().stream().filter(o -> descriptor.needs(o).stream().noneMatch(lacking::containsKey)).toList(),
                o -> table(o, w));
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            meter.sessionClosed();
            holds.forEach(MemoryBudget.Reservation::close);
        }
    }
}
