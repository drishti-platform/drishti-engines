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

import com.ash.engine.Answer;
import com.ash.engine.Answers;
import com.ash.engine.Cell;
import com.ash.engine.Control;
import com.ash.engine.EngineContext;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.EngineMeter;
import com.ash.engine.Explaining;
import com.ash.engine.Explanation;
import com.ash.engine.Filter;
import com.ash.engine.InputTable;
import com.ash.engine.Inputs;
import com.ash.engine.Missing;
import com.ash.engine.Request;
import com.ash.engine.Session;
import com.ash.engine.Snapshot;
import com.ash.engine.Table;
import com.ash.engine.TableSchema;
import com.ash.engine.Tables;
import com.ash.engines.query.exec.Executor;
import com.ash.engines.query.exec.Keys;
import com.ash.engines.query.out.Answering;
import com.ash.engines.query.out.CellExplainer;
import com.ash.engines.query.out.QueryWorkbook;
import com.ash.engines.query.plan.Documents;
import com.ash.engines.query.plan.Measures;
import com.ash.engines.query.plan.Plan;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A query session: the open input's handle and nothing else. Every ask reads the tables its document names, as the caller, plans,
 * computes and finishes; nothing derived from one ask is kept for another, so two visibilities never meet (laws {@code interleaving}
 * and {@code isolation} hold by construction).
 */
final class QuerySession implements Session, Explaining {

    static final String INPUT = "tables";

    private final QueryEngine engine;
    private final EngineContext context;
    private final EngineMeter meter;
    private final QueryEngine.Settings settings;
    private final Snapshot snapshot;
    private final Inputs inputs;
    private volatile boolean closed;

    QuerySession(QueryEngine engine, EngineContext context, EngineMeter meter, QueryEngine.Settings settings, Snapshot snapshot, Inputs inputs) {
        this.engine = engine;
        this.context = context;
        this.meter = meter;
        this.settings = settings;
        this.snapshot = snapshot;
        this.inputs = inputs;
        meter.sessionOpened();
    }

    @Override
    public Snapshot snapshot() {
        return snapshot;
    }

    @Override
    public long memoryBytes() {
        return 0;                                                    // nothing is held between asks
    }

    /** One plan, its table and the caller's rows of it; or the missing input. */
    record Asked(Plan plan, InputTable table, Filter visibility, Missing missing) {}

    @Override
    public Answer ask(Request request, Control control) throws EngineException {
        return meter.measure(() -> compute(request, control), 0, 0);
    }

    private Answer compute(Request request, Control control) throws EngineException {
        List<Table> tables = new ArrayList<>();
        List<Missing> missing = new ArrayList<>();
        Answering.Built built = null;
        for (String output : request.outputs()) {
            control.check();
            switch (output) {
                case "cells", "members" -> {
                    if (built == null) {
                        Asked a = asked(request, request.params());
                        if (a.missing() != null) {
                            missing.add(a.missing());
                            continue;
                        }
                        built = built(a, control).getKey();
                    }
                    tables.add("cells".equals(output) ? Tabling.cells(schema("cells"), built) : Tabling.members(schema("members"), built));
                }
                case "changes" -> {
                    Object c = changes(request, control);
                    if (c instanceof Missing m) {
                        missing.add(m);
                    } else {
                        tables.add((Table) c);
                    }
                }
                default -> throw new EngineException("ENG-3001", Kind.REFUSED, "the query engine has no output " + output);
            }
        }
        List<Missing> named = missing.stream().map(m -> new Missing(m.input(), m.contract(), m.snapshot(), m.columns(), request.outputs())).toList();
        return Answers.of(context, this, request, tables, named.stream().distinct().toList());
    }

    private TableSchema schema(String output) {
        return engine.descriptor().schema(output);
    }

    /** Reads the request's one document into a plan, finds its table as the caller, and checks the fields are there. */
    Asked asked(Request request, SortedMap<String, Object> params) throws EngineException {
        Plan plan = plan(params);
        InputTable t = inputs.table(INPUT, plan.table());
        if (!t.present()) {
            return new Asked(plan, t, null, new Missing(INPUT + ":" + plan.table(), null, snapshot, List.of(), List.of()));
        }
        List<String> lacking = plan.fields().stream().filter(f -> !t.columns().contains(f)).sorted().toList();
        if (!lacking.isEmpty()) {
            return new Asked(plan, t, null, new Missing(INPUT + ":" + plan.table(), null, snapshot, lacking, List.of()));
        }
        Filter f = request.visibility() == null ? null : request.visibility().rows().get(INPUT);
        return new Asked(plan, t, f == null ? Filter.ALL : f, null);
    }

    @SuppressWarnings("unchecked")
    Plan plan(SortedMap<String, Object> params) throws EngineException {
        List<String> given = new ArrayList<>();
        for (String k : List.of("view", "query", "derived")) {
            if (params.get(k) != null) {
                given.add(k);
            }
        }
        if (given.size() != 1) {
            throw new EngineException("QRY-1001", Kind.REFUSED, "a request gives exactly one of view, query and derived; this one gives "
                    + (given.isEmpty() ? "none" : String.join(" and ", given)));
        }
        String date = snapshot.dimensions().get("date");
        Map<String, Object> doc = (Map<String, Object>) params.get(given.get(0));
        try {
            return switch (given.get(0)) {
                case "view" -> Documents.view(doc, date);
                case "query" -> Documents.query(doc, date);
                default -> Documents.derived(doc);
            };
        } catch (Documents.RowsAsTheyAre e) {
            throw new EngineException("QRY-4001", Kind.REFUSED, e.getMessage());
        } catch (Measures.UnknownFunction e) {
            throw new EngineException("QRY-1004", Kind.REFUSED, e.getMessage());
        } catch (RuntimeException e) {
            String w = String.valueOf(e.getMessage());
            String code = w.contains("names itself") || w.contains("is already a share") || w.contains("two values are called") ? "QRY-1005"
                    : w.contains("inside an aggregate") || w.contains("is not an aggregate") ? "QRY-1004" : "QRY-1003";
            throw new EngineException(code, Kind.REFUSED, w);
        }
    }

    /** The answer of a plan, and the answering that made it (for an explanation). */
    Map.Entry<Answering.Built, Answering> built(Asked a, Control control) throws EngineException {
        Executor ex = new Executor(a.plan(), settings.batchRows());
        Answering ans = new Answering(a.plan(), ex);
        try {
            return Map.entry(ans.answer(a.table(), a.visibility(), settings.exactPath(),
                    new Answering.Budget(settings.maxCells(), settings.maxHolisticCells()), control), ans);
        } catch (Answering.OverBudget e) {
            throw new EngineException(e.holistic ? "QRY-2002" : "QRY-2001", Kind.LIMIT, e.getMessage(), Map.of("cells", Long.toString(e.cells)), null);
        } catch (IllegalArgumentException e) {
            throw new EngineException("QRY-1003", Kind.REFUSED, e.getMessage());
        }
    }

    /**
     * Why the cells moved: the view with the split dimension added as its innermost column, answered here and at the other date (this
     * engine there, through {@code EngineContext.peer}, under the same visibility); each cell's change by member of the split.
     */
    @SuppressWarnings("unchecked")
    private Object changes(Request request, Control control) throws EngineException {
        Object view = request.param("view");
        Object against = request.param("against");
        Object split = request.param("split-by");
        if (view == null || against == null || split == null) {
            throw new EngineException("QRY-1001", Kind.REFUSED, "changes needs view, against and split-by");
        }
        Map<String, Object> v = new LinkedHashMap<>((Map<String, Object>) view);
        List<Object> cols = new ArrayList<>(v.get("columns") instanceof List<?> l ? (List<Object>) l : List.of());
        if (cols.contains(split) || (v.get("rows") instanceof List<?> r && r.contains(split))) {
            throw new EngineException("QRY-1005", Kind.REFUSED, split + " is already on an axis of the view: split by another dimension");
        }
        cols.add(split);
        v.put("columns", cols);
        v.put("expandTo", Map.of("rows", v.get("expandTo") instanceof Map<?, ?> et && et.get("rows") instanceof Number n ? n : 1, "columns", cols.size()));
        SortedMap<String, Object> p = new TreeMap<>(Map.of("view", v));
        Asked a = asked(request, p);
        if (a.missing() != null) {
            return a.missing();
        }
        Table here = Tabling.cells(schema("cells"), built(a, control).getKey());
        Map<String, String> dims = new TreeMap<>(snapshot.dimensions());
        dims.put("date", String.valueOf(against));
        var peer = context.peer(Snapshot.of(dims));
        Table there = null;
        if (peer.isPresent()) {
            Answer other = peer.get().ask("cells", p, request, control);
            there = other.table("cells");
        }
        return Tabling.changes(schema("changes"), String.valueOf(split), here, there);
    }

    @Override
    public Explanation explain(Request request, Cell cell, Control control) throws EngineException {
        if (!"cells".equals(cell.output()) || !"NUMBER".equals(cell.column())) {
            throw new EngineException("QRY-3002", Kind.REFUSED, "only NUMBER of cells is explained here");
        }
        Asked a = asked(request, request.params());
        if (a.missing() != null) {
            throw new EngineException("QRY-3002", Kind.REFUSED, a.missing().words());
        }
        Map.Entry<Answering.Built, Answering> b = built(a, control);
        return new CellExplainer(a.plan(), new Executor(a.plan(), settings.batchRows()), b.getValue(), b.getKey(), settings.explainRows())
                .explain(cell, a.table(), a.visibility(), control);
    }

    @Override
    public void workbook(Request request, OutputStream out, Control control) throws EngineException, IOException {
        Asked a = asked(request, request.params());
        if (a.missing() != null) {
            throw new EngineException("QRY-3003", Kind.REFUSED, a.missing().words());
        }
        Map.Entry<Answering.Built, Answering> b = built(a, control);
        new QueryWorkbook(a.plan(), new Executor(a.plan(), settings.batchRows()), b.getKey(), settings.workbookRows())
                .write(out, a.table(), a.visibility(), control);
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            meter.sessionClosed();
        }
    }

    /** The canonical coordinates of a node key (for words). */
    static String words(String key) {
        return Keys.ALL.equals(key) ? "the total" : key;
    }

    /** The answer tables, built from a finished answer. */
    static final class Tabling {

        private Tabling() {}

        static Table cells(TableSchema s, Answering.Built b) {
            Tables.Builder t = Tables.of(s);
            for (Answering.Node r : b.rows()) {
                for (Answering.Node c : b.columns()) {
                    for (Plan.Value v : b.plan().values()) {
                        Answering.Figure f = b.cell(r.key(), c.key(), v.label());
                        if (f != null) {
                            t.row(r.key(), c.key(), v.label(), f.number(), f.text(), f.rows(), f.note());
                        }
                    }
                }
            }
            return t.build();
        }

        static Table members(TableSchema s, Answering.Built b) {
            Tables.Builder t = Tables.of(s);
            int[] ordinal = {0};
            boolean flat = b.plan().flat();
            for (List<Answering.Node> axis : List.of(b.rows(), b.columns())) {
                for (Answering.Node n : axis) {
                    String[] d = n.describe(flat && "rows".equals(n.axis()));
                    t.row(n.axis(), n.key(), Long.parseLong(d[0]), d[1], d[2], d[3], n.open(), n.others(), (long) ordinal[0]++);
                }
            }
            return t.build();
        }

        /** {@code changes} from the two answers of the split view: per cell and member, before, after and the change. */
        static Table changes(TableSchema s, String split, Table here, Table there) {
            Map<String, Double[]> rows = new TreeMap<>();
            for (int side = 0; side < 2; side++) {
                Table t = side == 0 ? there : here;
                if (t == null) {
                    continue;
                }
                for (int i = 0; i < t.rows(); i++) {
                    SortedMap<String, String> col = Keys.read(t.texts("COLUMN")[i]);
                    String by = col.remove(split);
                    String k = t.texts("ROW")[i] + "\u0000" + Keys.of(col) + "\u0000" + t.texts("VALUE")[i] + "\u0000" + (by == null ? Keys.ALL : by);
                    Double[] ba = rows.computeIfAbsent(k, x -> new Double[2]);
                    ba[side] = t.isNull("NUMBER", i) ? null : t.numbers("NUMBER")[i];
                }
            }
            Tables.Builder out = Tables.of(s);
            rows.forEach((k, ba) -> {
                String[] p = k.split("\u0000", -1);
                Double change = ba[0] == null && ba[1] == null ? null : (ba[1] == null ? 0 : ba[1]) - (ba[0] == null ? 0 : ba[0]);
                out.row(p[0], p[1], p[2], p[3], ba[0], ba[1], change);
            });
            return out.build();
        }
    }
}
