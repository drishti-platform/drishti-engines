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
package com.ash.engines.query.exec;

import com.ash.engine.Aggregate;
import com.ash.engine.Batch;
import com.ash.engine.Control;
import com.ash.engine.EngineException;
import com.ash.engine.Filter;
import com.ash.engine.GroupScan;
import com.ash.engine.InputTable;
import com.ash.engine.Scan;
import com.ash.engines.query.expr.Ast;
import com.ash.engines.query.expr.Eval;
import com.ash.engines.query.plan.Agg;
import com.ash.engines.query.plan.Plan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Computes the cells of some units of a plan, on one of two paths, and nothing else:
 *
 * <ul>
 * <li><b>push-down</b>: one grouped scan per unit (draft 1, open point O2 of QUERY_ENGINE.md: a batch does not say its grouping set),
 * the parts of every mergeable aggregate as the closed functions, the plan's condition, the unit's restriction and the caller's
 * visibility as one Rachana-EL condition. Taken only when the source offers every unit's scan, every aggregate is mergeable, every
 * dimension is a plain column and the exact path is off;</li>
 * <li><b>scan</b>: one scan of exactly the fields the plan reads; each row's members and values worked out here with Rachana-EL's
 * rules; the parts computed exactly (see {@link Cell}). The answer does not depend on batch size, row order or threads.</li>
 * </ul>
 *
 * The two paths give the same figures within the {@code relative:1e-12} the descriptor declares (sums from a source are rounded in
 * its own order); the scan path alone gives the same bits wherever it runs.
 */
public final class Executor {

    /** The cells of each unit, by canonical coordinates (grouped and fixed dimensions), in canonical order. */
    public record Result(Map<Unit, TreeMap<String, Cell>> cells, boolean pushed) {}

    private final Plan plan;
    private final List<Agg> aggs;
    private final List<Agg.Part> parts = new ArrayList<>();
    private final Map<Agg, int[]> partsOf = new LinkedHashMap<>();
    private final Map<Agg, Integer> holisticOf = new LinkedHashMap<>();
    private final int batchRows;

    public Executor(Plan plan, int batchRows) {
        this.plan = plan;
        this.aggs = plan.aggregates();
        this.batchRows = batchRows;
        parts.add(new Agg.Part(Aggregate.Fn.COUNT, null, null));             // part 0: the cell's rows
        Map<String, Integer> index = new HashMap<>();
        index.put(parts.get(0).key(), 0);
        for (Agg a : aggs) {
            if (a.fn().mergeable) {
                List<Agg.Part> ps = a.parts();
                int[] at = new int[ps.size()];
                for (int i = 0; i < ps.size(); i++) {
                    Agg.Part p = ps.get(i);
                    Integer k = index.get(p.key());
                    if (k == null) {
                        k = parts.size();
                        parts.add(p);
                        index.put(p.key(), k);
                    }
                    at[i] = k;
                }
                partsOf.put(a, at);
            } else {
                holisticOf.put(a, holisticOf.size());
            }
        }
    }

    /** The aggregates, in plan order. */
    public List<Agg> aggregates() {
        return aggs;
    }

    /** The figure of an aggregate in a cell (null: no values), or null when the cell is absent. */
    public Object figure(Agg a, Cell c) {
        if (c == null) {
            return a.fn() == Agg.Fn.COUNT || a.fn() == Agg.Fn.DISTINCT || a.fn() == Agg.Fn.COUNT_ONCE ? (Object) 0.0 : null;
        }
        int[] at = partsOf.get(a);
        if (at == null) {
            return Cell.finishHolistic(a, c.holistic(holisticOf.get(a)));
        }
        Object[] v = new Object[at.length];
        for (int i = 0; i < at.length; i++) {
            v[i] = c.part(at[i]);
        }
        return a.finish(v);
    }

    /** True when the plan may be asked of the source as grouped scans. */
    public boolean pushable(boolean exactPath) {
        if (exactPath || !holisticOf.isEmpty()) {
            return false;
        }
        for (Plan.Dim d : plan.axisDims()) {
            if (!d.calc() && !d.column()) {
                return false;
            }
        }
        return true;
    }

    /** Computes the units' cells. {@code visibility}: the caller's rows of the table, as the host gave them. */
    public Result run(List<Unit> units, InputTable table, Filter visibility, boolean exactPath, Control control) throws EngineException {
        if (pushable(exactPath)) {
            Ast seen = Filters.el(visibility);
            if (seen != null || visibility instanceof Filter.All) {
                List<GroupScan> scans = new ArrayList<>();
                boolean all = true;
                for (Unit u : units) {
                    GroupScan g = groupScan(u, seen);
                    scans.add(g);
                    if (!table.offers(g)) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    Map<Unit, TreeMap<String, Cell>> out = new LinkedHashMap<>();
                    for (int i = 0; i < units.size(); i++) {
                        out.put(units.get(i), pushed(units.get(i), scans.get(i), table, control));
                    }
                    return new Result(out, true);
                }
            }
        }
        return new Result(scanned(units, table, visibility, control), false);
    }

    private GroupScan groupScan(Unit u, Ast visibility) {
        List<Aggregate> as = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            Agg.Part p = parts.get(i);
            as.add(new Aggregate("p" + i, p.fn(), p.of() == null ? null : p.of().el(), p.where() == null ? null : p.where().el()));
        }
        Ast where = Ast.and(plan.where(), Ast.and(restriction(u), visibility));
        return new GroupScan(List.of(new ArrayList<>(u.dims())), as, where == null ? null : where.el(), batchRows);
    }

    /** A unit's restriction as a condition: its fixed members, and the Others restriction. */
    static Ast restriction(Unit u) {
        Ast w = null;
        for (Map.Entry<String, String> e : u.fixed().entrySet()) {
            Ast f = new Ast.Ref(e.getKey());
            w = Ast.and(w, Plan.BLANK.equals(e.getValue()) ? new Ast.Binary("==", f, new Ast.Null())
                    : new Ast.Binary("==", f, new Ast.Str(e.getValue())));
        }
        if (u.notInDim() != null) {
            List<Ast> items = new ArrayList<>();
            u.notIn().forEach(m -> items.add(Plan.BLANK.equals(m) ? new Ast.Null() : new Ast.Str(m)));
            w = Ast.and(w, new Ast.In(new Ast.Ref(u.notInDim()), new Ast.ListOf(items), true));
        }
        return w;
    }

    private TreeMap<String, Cell> pushed(Unit u, GroupScan g, InputTable table, Control control) throws EngineException {
        TreeMap<String, Cell> cells = new TreeMap<>();
        List<String> dims = g.sets().get(0);
        table.groups(g, b -> {
            control.check();
            Map<String, String[]> keys = new HashMap<>();
            for (String d : dims) {
                keys.put(d, b.texts(d));
            }
            Object[][] cols = new Object[parts.size()][];
            for (int i = 0; i < parts.size(); i++) {
                cols[i] = column(b, "p" + i, parts.get(i).fn());
            }
            for (int r = 0; r < b.rows(); r++) {
                Map<String, String> coords = new TreeMap<>(u.fixed());
                for (String d : dims) {
                    String m = b.isNull(d, r) ? null : keys.get(d)[r];
                    coords.put(d, m == null ? Plan.BLANK : m);
                }
                if (u.notInDim() != null) {
                    coords.put(u.notInDim(), Plan.OTHERS);
                }
                Cell c = new Cell(parts.size(), 0);
                for (int i = 0; i < parts.size(); i++) {
                    c.set(i, cols[i][r]);
                }
                cells.put(Keys.of(coords), c);
            }
        });
        return cells;
    }

    private static Object[] column(Batch b, String name, Aggregate.Fn fn) {
        Object[] out = new Object[b.rows()];
        if (fn == Aggregate.Fn.COUNT) {
            long[] v = b.integers(name);
            for (int r = 0; r < out.length; r++) {
                out[r] = b.isNull(name, r) ? 0L : v[r];
            }
        } else {
            double[] v = b.numbers(name);
            for (int r = 0; r < out.length; r++) {
                out[r] = b.isNull(name, r) ? null : (Object) v[r];
            }
        }
        return out;
    }

    private Map<Unit, TreeMap<String, Cell>> scanned(List<Unit> units, InputTable table, Filter visibility, Control control)
            throws EngineException {
        Map<Unit, TreeMap<String, Cell>> out = new LinkedHashMap<>();
        units.forEach(u -> out.put(u, new TreeMap<>()));
        List<String> columns = new ArrayList<>(plan.fields());
        columns.removeIf(c -> !table.columns().contains(c));
        Map<String, Plan.Dim> dims = new LinkedHashMap<>();
        for (Unit u : units) {
            for (String id : u.known()) {
                Plan.Dim d = plan.dim(id);
                dims.put(id, d == null ? Plan.Dim.parse(id) : d);
            }
        }
        List<Agg> holistic = new ArrayList<>(holisticOf.keySet());
        table.scan(new Scan(columns, visibility, batchRows), b -> {
            control.check();
            Rows rows = new Rows(b, columns);
            for (int r = 0; r < b.rows(); r++) {
                final int row = r;
                java.util.function.Function<String, Object> field = c -> rows.value(c, row);
                Eval eval = new Eval(field, c -> {
                    throw new IllegalArgumentException("an aggregate in a row expression: " + c.el());
                });
                if (plan.where() != null && !Eval.truthy(eval.eval(plan.where()))) {
                    continue;
                }
                Map<String, String> members = new HashMap<>();
                for (Map.Entry<String, Plan.Dim> e : dims.entrySet()) {
                    if (!e.getValue().calc()) {
                        members.put(e.getKey(), e.getValue().member(field));
                    }
                }
                Map<Ast, Boolean> conds = new HashMap<>();
                Map<Ast, Object> vals = new HashMap<>();
                for (Unit u : units) {
                    if (!in(u, members)) {
                        continue;
                    }
                    Map<String, String> coords = new TreeMap<>(u.fixed());
                    for (String d : u.dims()) {
                        coords.put(d, members.get(d));
                    }
                    if (u.notInDim() != null) {
                        coords.put(u.notInDim(), Plan.OTHERS);
                    }
                    Cell c = out.get(u).computeIfAbsent(Keys.of(coords), k -> new Cell(parts.size(), holistic.size()));
                    for (int i = 0; i < parts.size(); i++) {
                        Agg.Part p = parts.get(i);
                        if (p.where() != null && !conds.computeIfAbsent(p.where(), w -> Eval.truthy(eval.eval(w)))) {
                            continue;
                        }
                        c.add(i, p.fn(), p.of() == null ? null : vals.computeIfAbsent(p.of(), eval::eval));
                    }
                    for (int i = 0; i < holistic.size(); i++) {
                        Agg a = holistic.get(i);
                        if (a.where() != null && !conds.computeIfAbsent(a.where(), w -> Eval.truthy(eval.eval(w)))) {
                            continue;
                        }
                        c.addHolistic(i, a, a.arg() == null ? null : vals.computeIfAbsent(a.arg(), eval::eval),
                                a.arg2() == null ? null : vals.computeIfAbsent(a.arg2(), eval::eval));
                    }
                }
            }
        });
        return out;
    }

    /**
     * The rows of one cell, as plain values of {@code fields} (for an explanation or a workbook): the plan's condition, the cell's
     * coordinates (calculation groups left out) and {@code extra} hold. Stops with {@code QRY-3003}'s words past {@code max} rows.
     */
    public List<Map<String, Object>> rows(InputTable table, Filter visibility, Map<String, String> coords, String notInDim, Set<String> notIn,
            Ast extra, List<String> fields, long max, Control control) throws EngineException {
        List<String> columns = new ArrayList<>(plan.fields());
        columns.removeIf(c -> !table.columns().contains(c));
        List<Map<String, Object>> out = new ArrayList<>();
        table.scan(new Scan(columns, visibility, batchRows), b -> {
            control.check();
            Rows rows = new Rows(b, columns);
            for (int r = 0; r < b.rows(); r++) {
                final int row = r;
                java.util.function.Function<String, Object> field = c -> rows.value(c, row);
                Eval eval = new Eval(field, c -> {
                    throw new IllegalArgumentException("an aggregate in a row expression: " + c.el());
                });
                if (plan.where() != null && !Eval.truthy(eval.eval(plan.where()))) {
                    continue;
                }
                if (extra != null && !Eval.truthy(eval.eval(extra))) {
                    continue;
                }
                boolean ok = true;
                for (Map.Entry<String, String> e : coords.entrySet()) {
                    Plan.Dim d = plan.dim(e.getKey());
                    d = d == null ? Plan.Dim.parse(e.getKey()) : d;
                    if (d.calc()) {
                        continue;
                    }
                    String m = d.member(field);
                    if (e.getKey().equals(notInDim) ? notIn.contains(m) : !e.getValue().equals(m)) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) {
                    continue;
                }
                if (out.size() >= max) {
                    throw new EngineException("QRY-3003", EngineException.Kind.LIMIT, "the cell holds more than " + max + " rows");
                }
                Map<String, Object> m = new LinkedHashMap<>();
                for (String f : fields) {
                    m.put(f, field.apply(f));
                }
                out.add(m);
            }
        });
        return out;
    }

    private static boolean in(Unit u, Map<String, String> members) {
        for (Map.Entry<String, String> e : u.fixed().entrySet()) {
            if (!e.getValue().equals(members.get(e.getKey()))) {
                return false;
            }
        }
        return u.notInDim() == null || !u.notIn().contains(members.get(u.notInDim()));
    }

    /**
     * A batch's columns as values: a column is read as numbers when the batch holds it as numbers, else as text (an open table's types
     * are not in the API yet: open point O16 of QUERY_ENGINE.md). Read once per batch and column.
     */
    private static final class Rows {
        private final Batch b;
        private final Set<String> columns;
        private final Map<String, Object> arrays = new HashMap<>();

        Rows(Batch b, List<String> columns) {
            this.b = b;
            this.columns = Set.copyOf(columns);
        }

        Object value(String c, int r) {
            if (!columns.contains(c)) {
                return null;
            }
            Object a = arrays.computeIfAbsent(c, k -> {
                try {
                    return b.numbers(k);
                } catch (RuntimeException notNumbers) {
                    return b.texts(k);
                }
            });
            if (b.isNull(c, r)) {
                return null;
            }
            if (a instanceof double[] d) {
                return Double.isNaN(d[r]) ? null : (Object) d[r];
            }
            return ((String[]) a)[r];
        }
    }
}
