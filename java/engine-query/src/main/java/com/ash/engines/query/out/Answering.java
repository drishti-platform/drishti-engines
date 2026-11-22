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
package com.ash.engines.query.out;

import com.ash.engine.Control;
import com.ash.engine.EngineException;
import com.ash.engine.Filter;
import com.ash.engine.InputTable;
import com.ash.engines.query.exec.Cell;
import com.ash.engines.query.exec.Executor;
import com.ash.engines.query.exec.Keys;
import com.ash.engines.query.exec.Unit;
import com.ash.engines.query.expr.Ast;
import com.ash.engines.query.expr.Eval;
import com.ash.engines.query.plan.Agg;
import com.ash.engines.query.plan.Measures;
import com.ash.engines.query.plan.Plan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Answers a plan: the groupings of each axis (totals, levels, opened nodes, a flat model query's rows), the units they make, the
 * cells computed once, the axis nodes read from the cells, top N with an Others member computed as its own unit (a second pass),
 * sorting, every value of every cell finished from its aggregates, and show-as over the finished figures. Nothing here reads a row:
 * the {@link Executor} does.
 *
 * <p>A total is its own unit's state, never a sum of the figures shown; a share's denominator ({@code calc(..., ignore = ...)}) is the
 * unit with those dimensions taken out.
 */
public final class Answering {

    /** One grouping of an axis: the dimensions of its nodes in path order, the members every node of it has, hidden or shown. */
    record Grouping(String axis, List<String> dims, SortedMap<String, String> fixed, boolean hidden) {
        String key() {
            return axis + ":" + dims + fixed;
        }
    }

    /** A node of an axis. */
    public static final class Node {
        final String axis;
        final List<String[]> path;
        final Grouping grouping;
        final boolean others;
        final String key;
        final List<Node> children = new ArrayList<>();
        Node parent;
        boolean hidden;
        Unit othersUnitOf;
        String othersDim;
        Set<String> othersKept;

        Node(String axis, List<String[]> path, Grouping grouping, boolean others) {
            this.axis = axis;
            this.path = List.copyOf(path);
            this.grouping = grouping;
            this.others = others;
            Map<String, String> m = new TreeMap<>();
            path.forEach(p -> m.put(p[0], p[1]));
            this.key = Keys.of(m);
        }

        public String key() {
            return key;
        }

        public String axis() {
            return axis;
        }

        public boolean others() {
            return others;
        }

        /** True when its children were computed. */
        public boolean open() {
            return !children.isEmpty();
        }

        /**
         * Depth, dimension, member and parent path as the members table shows them. A flat model query's row is one level: its
         * dimension the fields joined by {@code |}, its member its coordinates.
         */
        public String[] describe(boolean flat) {
            if (path.isEmpty()) {
                return new String[] {"0", null, null, null};
            }
            if (flat) {
                List<String> dims = new ArrayList<>();
                path.forEach(p -> dims.add(p[0]));
                return new String[] {"1", String.join("|", dims), key, Keys.ALL};
            }
            return new String[] {Integer.toString(path.size()), dim(), member(), parentKey()};
        }

        String parentKey() {
            if (path.isEmpty()) {
                return null;
            }
            Map<String, String> m = new TreeMap<>();
            path.subList(0, path.size() - 1).forEach(p -> m.put(p[0], p[1]));
            return Keys.of(m);
        }

        String dim() {
            return path.isEmpty() ? null : path.get(path.size() - 1)[0];
        }

        String member() {
            return path.isEmpty() ? null : path.get(path.size() - 1)[1];
        }
    }

    /** The finished answer: both axes in order, and every cell's figure, rows and note. */
    public record Built(List<Node> rows, List<Node> columns, Map<String, Figure> cells, boolean pushed, Plan plan) {
        /** The figure of a cell, by its row, column and value. */
        public Figure cell(String row, String column, String value) {
            return cells.get(row + "\u0000" + column + "\u0000" + value);
        }
    }

    /** One cell's figure: a number or a text, its rows, why it is empty. */
    public record Figure(Double number, String text, long rows, String note) {}

    /** The budget an answer must fit. */
    public record Budget(int maxCells, int maxHolisticCells) {}

    /** Thrown when an answer would be over its budget (the session turns it into QRY-2001 or QRY-2002). */
    public static final class OverBudget extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final long cells;
        public final boolean holistic;

        OverBudget(long cells, long max, boolean holistic) {
            super("the answer would hold " + cells + " cells, more than the " + max + " allowed" + (holistic ? " with a holistic value" : "")
                    + ": open fewer nodes, filter, or ask fewer values");
            this.cells = cells;
            this.holistic = holistic;
        }
    }

    private final Plan plan;
    private final Executor executor;
    private final Map<Unit, TreeMap<String, Cell>> results = new HashMap<>();
    private final Map<String, Unit> unitOf = new HashMap<>();
    private final List<Set<String>> ignores = new ArrayList<>();
    private boolean pushed = true;
    private Map<String, Node> rowNodes = Map.of();
    private Map<String, Node> colNodes = Map.of();

    public Answering(Plan plan, Executor executor) {
        this.plan = plan;
        this.executor = executor;
        Set<Set<String>> seen = new LinkedHashSet<>();
        plan.formulas().forEach(f -> seen.addAll(Measures.ignores(f)));
        ignores.addAll(seen);
    }

    /** Computes and finishes the answer. */
    public Built answer(InputTable table, Filter visibility, boolean exactPath, Budget budget, Control control) throws EngineException {
        List<Grouping> rowGs = groupings("rows", plan.rows(), plan.alsoRows(), plan.expandRows(), plan.flat(), plan.expanded());
        List<Grouping> colGs = groupings("columns", plan.columns(), List.of(), plan.expandColumns(), false, plan.expanded());
        List<Unit> units = new ArrayList<>();
        for (Grouping r : rowGs) {
            for (Grouping c : colGs) {
                Unit u = unit(r, c);
                unitOf.put(r.key() + "\u0000" + c.key(), u);
                addWithIgnores(units, u);
            }
        }
        run(units, table, visibility, exactPath, control);
        Map<String, Node> rowNodes = nodes(rowGs, colGs, true);
        Map<String, Node> colNodes = nodes(colGs, rowGs, false);
        this.rowNodes = rowNodes;
        this.colNodes = colNodes;
        long visible = rowNodes.values().stream().filter(n -> !n.hidden).count() * colNodes.values().stream().filter(n -> !n.hidden).count()
                * plan.values().size();
        boolean holistic = !executor.aggregates().stream().allMatch(a -> a.fn().mergeable);
        long max = holistic ? Math.min(budget.maxHolisticCells(), budget.maxCells()) : budget.maxCells();
        if (visible > max) {
            throw new OverBudget(visible, max, holistic);
        }
        Node rowTotal = rowNodes.get(Keys.ALL);
        Node colTotal = colNodes.get(Keys.ALL);
        List<Unit> others = new ArrayList<>();
        top(rowNodes, colNodes, colTotal, true, colGs, others);
        top(colNodes, rowNodes, rowTotal, false, rowGs, others);
        if (!others.isEmpty()) {
            run(others, table, visibility, exactPath, control);
        }
        List<Node> rows = ordered(rowNodes, colTotal, true);
        List<Node> cols = ordered(colNodes, rowTotal, false);
        Map<String, Figure> cells = new LinkedHashMap<>();
        Map<String, Object> base = new HashMap<>();
        for (Node r : rows) {
            for (Node c : cols) {
                control.check();
                for (Plan.Value v : plan.values()) {
                    base.put(r.key + "\u0000" + c.key + "\u0000" + v.label(), value(v, r, c));
                }
            }
        }
        for (Node r : rows) {
            for (Node c : cols) {
                if (r.hidden || c.hidden) {
                    continue;
                }
                for (Plan.Value v : plan.values()) {
                    Object x = base.get(r.key + "\u0000" + c.key + "\u0000" + v.label());
                    String note = null;
                    if (v.showAs() != null && x != null) {
                        Object[] s = showAs(v, r, c, x, base, rowNodes, colNodes, rows, cols);
                        x = s[0];
                        note = (String) s[1];
                    }
                    Double number = x instanceof Double d && Double.isFinite(d) ? d : null;
                    String text = x instanceof String s ? s : x instanceof Boolean b ? b.toString() : null;
                    if (x instanceof Double d && !Double.isFinite(d)) {
                        note = "not a finite number";
                    }
                    if (number == null && text == null && note == null) {
                        note = emptyNote(v);
                    }
                    cells.put(r.key + "\u0000" + c.key + "\u0000" + v.label(), new Figure(number, text, rows(r, c), note));
                }
            }
        }
        return new Built(rows.stream().filter(n -> !n.hidden).toList(), cols.stream().filter(n -> !n.hidden).toList(), cells, pushed, plan);
    }

    private void run(List<Unit> units, InputTable table, Filter visibility, boolean exactPath, Control control) throws EngineException {
        List<Unit> todo = units.stream().filter(u -> !results.containsKey(u)).distinct().toList();
        if (todo.isEmpty()) {
            return;
        }
        Executor.Result r = executor.run(todo, table, visibility, exactPath, control);
        results.putAll(r.cells());
        pushed &= r.pushed();
    }

    private void addWithIgnores(List<Unit> units, Unit u) {
        units.add(u);
        for (Set<String> i : ignores) {
            units.add(u.ignoring(i));
        }
    }

    // ---- groupings and units

    private List<Grouping> groupings(String axis, List<Plan.Dim> dims, List<Plan.Dim> also, int expandTo, boolean flat,
            List<Plan.Expansion> expanded) {
        List<Grouping> out = new ArrayList<>();
        List<String> ids = dims.stream().map(Plan.Dim::id).toList();
        boolean totals = plan.totals() || dims.isEmpty() && also.isEmpty();
        out.add(new Grouping(axis, List.of(), new TreeMap<>(), !totals));      // the total: hidden when the answer has none
        if (flat) {
            if (!ids.isEmpty()) {
                out.add(new Grouping(axis, ids, new TreeMap<>(), false));
            }
            return out;
        }
        int deepest = Math.min(Math.max(1, expandTo), ids.size());
        for (int d = 1; d <= deepest; d++) {
            if (plan.totals() || d == deepest) {
                out.add(new Grouping(axis, ids.subList(0, d), new TreeMap<>(), false));
            } else {
                out.add(new Grouping(axis, ids.subList(0, d), new TreeMap<>(), true));
            }
        }
        for (Plan.Dim a : also) {
            out.add(new Grouping(axis, List.of(a.id()), new TreeMap<>(), false));
        }
        for (Plan.Expansion e : expanded) {
            if (!axis.equals(e.axis())) {
                continue;
            }
            List<String> path = e.path().stream().map(p -> p.get(0)).toList();
            String by = e.by();
            if (by == null) {
                by = ids.stream().filter(x -> !path.contains(x)).findFirst().orElse(null);
            }
            if (by == null) {
                continue;                                            // a leaf: nothing below it
            }
            if (plan.dim(by) == null && !path.contains(by)) {
                throw new IllegalArgumentException("a node is opened by " + by + ", which is not a dimension of the " + axis);
            }
            List<String> gd = new ArrayList<>(path);
            gd.add(by);
            TreeMap<String, String> fixed = new TreeMap<>();
            e.path().forEach(p -> fixed.put(p.get(0), p.get(1)));
            out.add(new Grouping(axis, gd, fixed, false));
        }
        return out;
    }

    private Unit unit(Grouping r, Grouping c) {
        Set<String> dims = new TreeSet<>();
        Map<String, String> fixed = new TreeMap<>();
        for (Grouping g : List.of(r, c)) {
            for (String d : g.dims()) {
                if (!calc(d) && !g.fixed().containsKey(d)) {
                    dims.add(d);
                }
            }
            g.fixed().forEach((k, v) -> {
                if (!calc(k)) {
                    fixed.put(k, v);
                }
            });
        }
        return Unit.of(dims, fixed);
    }

    private boolean calc(String dimId) {
        Plan.Dim d = plan.dim(dimId);
        return d != null && d.calc();
    }

    // ---- nodes

    private Map<String, Node> nodes(List<Grouping> mine, List<Grouping> other, boolean rowsAxis) {
        Map<String, Node> out = new LinkedHashMap<>();
        for (Grouping g : mine) {
            TreeMap<String, List<String[]>> paths = new TreeMap<>();
            for (Grouping o : other) {
                Unit u = rowsAxis ? unitOf.get(g.key() + "\u0000" + o.key()) : unitOf.get(o.key() + "\u0000" + g.key());
                for (String k : results.getOrDefault(u, new TreeMap<>()).keySet()) {
                    SortedMap<String, String> coords = Keys.read(k);
                    for (List<String[]> p : expand(g, coords)) {
                        Map<String, String> m = new TreeMap<>();
                        p.forEach(x -> m.put(x[0], x[1]));
                        paths.putIfAbsent(Keys.of(m), p);
                    }
                }
            }
            if (g.dims().isEmpty()) {
                paths.putIfAbsent(Keys.ALL, List.of());
            }
            for (List<String[]> p : paths.values()) {
                Node n = new Node(g.axis(), p, g, false);
                if (!out.containsKey(n.key)) {
                    n.hidden = g.hidden();
                    out.put(n.key, n);
                }
            }
        }
        link(out);
        return out;
    }

    /** The paths of a grouping a cell's coordinates give: calculation groups' items multiply them. */
    private List<List<String[]>> expand(Grouping g, SortedMap<String, String> coords) {
        List<List<String[]>> out = new ArrayList<>();
        out.add(new ArrayList<>());
        for (String d : g.dims()) {
            List<String> members;
            if (calc(d)) {
                String item = g.fixed().get(d);
                members = item != null ? List.of(item) : new ArrayList<>(plan.calcGroups().getOrDefault(plan.dim(d).calcGroup(), Map.of()).keySet());
            } else {
                String m = coords.get(d);
                if (m == null) {
                    return List.of();
                }
                members = List.of(m);
            }
            List<List<String[]>> next = new ArrayList<>();
            for (List<String[]> p : out) {
                for (String m : members) {
                    List<String[]> q = new ArrayList<>(p);
                    q.add(new String[] {d, m});
                    next.add(q);
                }
            }
            out = next;
        }
        return out;
    }

    private static void link(Map<String, Node> nodes) {
        nodes.values().forEach(n -> n.children.clear());
        for (Node n : nodes.values()) {
            String pk = n.parentKey();
            Node p = pk == null ? null : nodes.get(pk);
            n.parent = p;
            if (p != null) {
                p.children.add(n);
            }
        }
    }

    // ---- top N and Others

    private void top(Map<String, Node> nodes, Map<String, Node> otherAxis, Node otherTotal, boolean rowsAxis, List<Grouping> otherGs,
            List<Unit> units) {
        if (plan.top().isEmpty() || otherTotal == null) {
            return;
        }
        Map<String, List<Node>> byParent = new LinkedHashMap<>();
        for (Node n : nodes.values()) {
            if (n.dim() != null && plan.top().containsKey(n.dim()) && !n.others) {
                byParent.computeIfAbsent(n.parentKey() + "\u0000" + n.dim(), k -> new ArrayList<>()).add(n);
            }
        }
        for (List<Node> sibs : byParent.values()) {
            String dim = sibs.get(0).dim();
            Plan.Top t = plan.top().get(dim);
            if (sibs.size() <= t.n()) {
                continue;
            }
            Plan.Value by = value(t.by());
            List<Node> ranked = new ArrayList<>(sibs);
            Map<Node, Double> score = new HashMap<>();
            for (Node n : ranked) {
                Object v = rowsAxis ? value(by, n, otherTotal) : value(by, otherTotal, n);
                score.put(n, v instanceof Double d && Double.isFinite(d) ? d : Double.NEGATIVE_INFINITY);
            }
            ranked.sort(Comparator.<Node, Double>comparing(score::get).reversed().thenComparing(n -> n.member(), Answering::members));
            List<Node> kept = ranked.subList(0, t.n());
            Set<String> keptMembers = new TreeSet<>();
            kept.forEach(k -> keptMembers.add(k.member()));
            for (Node cut : ranked.subList(t.n(), ranked.size())) {
                remove(nodes, cut);
            }
            Node parent = sibs.get(0).parent;
            List<String[]> path = new ArrayList<>(parent == null ? List.of() : parent.path);
            path.add(new String[] {dim, Plan.OTHERS});
            Node o = new Node(sibs.get(0).axis, path, sibs.get(0).grouping, true);
            o.othersDim = dim;
            o.othersKept = keptMembers;
            nodes.put(o.key, o);
            for (Grouping g : otherGs) {
                TreeMap<String, String> fixed = new TreeMap<>(g.fixed());
                for (String[] p : parent == null ? List.<String[]>of() : parent.path) {
                    fixed.put(p[0], p[1]);
                }
                fixed.keySet().removeIf(this::calc);
                Set<String> dims = new TreeSet<>();
                g.dims().stream().filter(d -> !calc(d) && !g.fixed().containsKey(d)).forEach(dims::add);
                Unit u = new Unit(new TreeSet<>(dims), fixed, dim, new TreeSet<>(keptMembers));
                unitOf.put("others\u0000" + o.key + "\u0000" + g.key(), u);
                addWithIgnores(units, u);
            }
        }
        link(nodes);
    }

    private static void remove(Map<String, Node> nodes, Node n) {
        nodes.remove(n.key);
        for (Node c : List.copyOf(n.children)) {
            remove(nodes, c);
        }
    }

    // ---- order

    private List<Node> ordered(Map<String, Node> nodes, Node otherTotal, boolean rowsAxis) {
        List<Node> out = new ArrayList<>();
        List<Node> roots = nodes.values().stream().filter(n -> n.parent == null).toList();
        if (plan.flat() && rowsAxis) {
            List<Node> leaves = new ArrayList<>(nodes.values().stream().filter(n -> !n.path.isEmpty()).toList());
            leaves.sort(flatOrder(otherTotal));
            if (plan.limit() != null && leaves.size() > plan.limit()) {
                leaves = new ArrayList<>(leaves.subList(0, plan.limit()));
            }
            Node total = nodes.get(Keys.ALL);
            if (total != null) {
                out.add(total);
            }
            out.addAll(leaves);
            return out;
        }
        List<Node> sortedRoots = new ArrayList<>(roots);
        sortedRoots.sort(Comparator.comparing((Node n) -> n.path.size()).thenComparing(n -> n.key));
        for (Node r : sortedRoots) {
            walk(r, out, otherTotal, rowsAxis);
        }
        return out;
    }

    private void walk(Node n, List<Node> out, Node otherTotal, boolean rowsAxis) {
        out.add(n);
        List<Node> kids = new ArrayList<>(n.children);
        kids.sort(siblingOrder(otherTotal, rowsAxis));
        for (Node k : kids) {
            walk(k, out, otherTotal, rowsAxis);
        }
    }

    private Comparator<Node> siblingOrder(Node otherTotal, boolean rowsAxis) {
        return (a, b) -> {
            if (a.others != b.others) {
                return a.others ? 1 : -1;
            }
            if (!java.util.Objects.equals(a.dim(), b.dim())) {
                return String.valueOf(a.dim()).compareTo(String.valueOf(b.dim()));
            }
            Plan.Sort s = plan.sort().get(a.dim());
            int c;
            if (s == null || s.by() == null || "label".equals(s.by())) {
                c = calc(a.dim()) ? Integer.compare(itemIndex(a), itemIndex(b)) : members(a.member(), b.member());
            } else {
                Plan.Value v = value(s.by());
                c = Double.compare(score(v, a, otherTotal, rowsAxis), score(v, b, otherTotal, rowsAxis));
                if (c == 0) {
                    c = members(a.member(), b.member());
                }
            }
            return s != null && s.desc() ? -c : c;
        };
    }

    private Comparator<Node> flatOrder(Node otherTotal) {
        return (a, b) -> {
            for (Plan.Sort s : plan.order()) {
                int c;
                Plan.Dim d = plan.dim(s.by());
                if (d != null) {
                    c = members(member(a, d.id()), member(b, d.id()));
                } else {
                    Plan.Value v = value(s.by());
                    c = Double.compare(score(v, a, otherTotal, true), score(v, b, otherTotal, true));
                }
                if (c != 0) {
                    return s.desc() ? -c : c;
                }
            }
            return a.key.compareTo(b.key);
        };
    }

    private static String member(Node n, String dim) {
        for (String[] p : n.path) {
            if (p[0].equals(dim)) {
                return p[1];
            }
        }
        return "";
    }

    private int itemIndex(Node n) {
        Plan.Dim d = plan.dim(n.dim());
        return new ArrayList<>(plan.calcGroups().getOrDefault(d.calcGroup(), Map.of()).keySet()).indexOf(n.member());
    }

    private double score(Plan.Value v, Node n, Node otherTotal, boolean rowsAxis) {
        if (otherTotal == null) {
            return Double.NEGATIVE_INFINITY;
        }
        Object x = rowsAxis ? value(v, n, otherTotal) : value(v, otherTotal, n);
        return x instanceof Double d && Double.isFinite(d) ? d : Double.NEGATIVE_INFINITY;
    }

    /** Members in order: two numbers by value, else by text; the blank member last. */
    static int members(String a, String b) {
        if (Plan.BLANK.equals(a) != Plan.BLANK.equals(b)) {
            return Plan.BLANK.equals(a) ? 1 : -1;
        }
        double x = Eval.number(a);
        double y = Eval.number(b);
        if (!Double.isNaN(x) && !Double.isNaN(y) && x != y) {
            return Double.compare(x, y);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private Plan.Value value(String label) {
        if (label == null) {
            return plan.values().get(0);
        }
        for (Plan.Value v : plan.values()) {
            if (v.label().equals(label)) {
                return v;
            }
        }
        throw new IllegalArgumentException("sort or top by " + label + ", which is not a value of the answer");
    }

    // ---- figures

    /** The unit and coordinates of a (row node, column node) pair, or null when it was not computed. */
    private Object[] where(Node r, Node c) {
        Unit u;
        if (r.others && c.others) {
            return null;
        }
        if (r.others) {
            u = unitOf.get("others\u0000" + r.key + "\u0000" + c.grouping.key());
        } else if (c.others) {
            u = unitOf.get("others\u0000" + c.key + "\u0000" + r.grouping.key());
        } else {
            u = unitOf.get(r.grouping.key() + "\u0000" + c.grouping.key());
        }
        if (u == null) {
            return null;
        }
        Map<String, String> coords = new TreeMap<>();
        for (Node n : List.of(r, c)) {
            for (String[] p : n.path) {
                if (!calc(p[0])) {
                    coords.put(p[0], p[1]);
                }
            }
        }
        return new Object[] {u, coords};
    }

    private long rows(Node r, Node c) {
        Object[] w = where(r, c);
        if (w == null) {
            return 0;
        }
        Cell cell = results.getOrDefault((Unit) w[0], new TreeMap<>()).get(Keys.of(castMap(w[1])));
        return cell == null ? 0 : cell.rows();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> castMap(Object o) {
        return (Map<String, String>) o;
    }

    /** A value of a cell before show-as: a Double, a String, or null. */
    public Object value(Plan.Value v, Node r, Node c) {
        Object[] w = where(r, c);
        if (w == null) {
            return null;
        }
        Ast f = v.formula();
        for (Node n : List.of(r, c)) {
            for (String[] p : n.path) {
                if (calc(p[0])) {
                    Ast item = plan.calcGroups().getOrDefault(plan.dim(p[0]).calcGroup(), Map.of()).get(p[1]);
                    if (item != null) {
                        f = Measures.selected(item, f);
                    }
                }
            }
        }
        return evaluate(f, (Unit) w[0], castMap(w[1]));
    }

    private Object evaluate(Ast f, Unit u, Map<String, String> coords) {
        return new Eval(name -> {
            throw new IllegalArgumentException(name + " is a field outside an aggregate");
        }, call -> {
            if ("calc".equals(call.name())) {
                Set<String> ign = Measures.ignored(call);
                Map<String, String> k = new TreeMap<>(coords);
                k.keySet().removeAll(ign);
                if (u.notInDim() != null && !ign.contains(u.notInDim())) {
                    k.put(u.notInDim(), Plan.OTHERS);
                }
                return evaluate(call.args().get(0), u.ignoring(ign), k);
            }
            Agg a = Measures.agg(call);
            return executor.figure(a, results.getOrDefault(u, new TreeMap<>()).get(Keys.of(coords)));
        }).eval(f);
    }

    /**
     * One aggregate of a cell, where its rows are: the explanation reads them.
     *
     * @param call the aggregate as the formula writes it
     * @param agg the aggregate
     * @param coords the coordinates of its rows (calculation groups left out; an ignored dimension taken out)
     * @param notInDim an Others restriction's dimension, or null
     * @param notIn the members an Others restriction leaves out
     * @param figure the aggregate's figure in the cell
     */
    public record AggAt(Ast.Call call, Agg agg, Map<String, String> coords, String notInDim, Set<String> notIn, Object figure) {}

    /** The formula of a value at a cell (calculation items applied) and each aggregate it reads there; null when the cell is not in the answer. */
    public Map.Entry<Ast, List<AggAt>> aggregatesOf(Plan.Value v, String rowKey, String colKey) {
        Node r = rowNodes.get(rowKey);
        Node c = colNodes.get(colKey);
        if (r == null || c == null || where(r, c) == null) {
            return null;
        }
        Object[] w = where(r, c);
        Ast f = v.formula();
        for (Node n : List.of(r, c)) {
            for (String[] p : n.path) {
                if (calc(p[0])) {
                    Ast item = plan.calcGroups().getOrDefault(plan.dim(p[0]).calcGroup(), Map.of()).get(p[1]);
                    if (item != null) {
                        f = Measures.selected(item, f);
                    }
                }
            }
        }
        List<AggAt> out = new ArrayList<>();
        collect(f, (Unit) w[0], castMap(w[1]), out);
        return Map.entry(f, out);
    }

    private void collect(Ast f, Unit u, Map<String, String> coords, List<AggAt> out) {
        if (f instanceof Ast.Call call && "calc".equals(call.name())) {
            Set<String> ign = Measures.ignored(call);
            Map<String, String> k = new TreeMap<>(coords);
            k.keySet().removeAll(ign);
            collect(call.args().get(0), u.ignoring(ign), k, out);
            return;
        }
        if (f instanceof Ast.Call call && Agg.Fn.named(call.name()) != null) {
            Agg a = Measures.agg(call);
            out.add(new AggAt(call, a, coords, u.notInDim(), u.notIn(),
                    executor.figure(a, results.getOrDefault(u, new TreeMap<>()).get(Keys.of(coords)))));
            return;
        }
        switch (f) {
            case Ast.Call call -> {
                call.args().forEach(x -> collect(x, u, coords, out));
                call.named().values().forEach(x -> collect(x, u, coords, out));
            }
            case Ast.Binary b -> {
                collect(b.left(), u, coords, out);
                collect(b.right(), u, coords, out);
            }
            case Ast.Unary x -> collect(x.operand(), u, coords, out);
            case Ast.Ternary t -> {
                collect(t.condition(), u, coords, out);
                collect(t.then(), u, coords, out);
                collect(t.otherwise(), u, coords, out);
            }
            case Ast.In in -> {
                collect(in.value(), u, coords, out);
                collect(in.list(), u, coords, out);
            }
            case Ast.ListOf l -> l.items().forEach(x -> collect(x, u, coords, out));
            default -> { }
        }
    }

    /** A value at a cell before show-as (null when the cell is not in the answer). */
    public Object valueAt(Plan.Value v, String rowKey, String colKey) {
        Node r = rowNodes.get(rowKey);
        Node c = colNodes.get(colKey);
        return r == null || c == null ? null : value(new Plan.Value(v.label(), v.formula(), null), r, c);
    }

    /** The figure a show-as divides by, at a cell: the grand total, the row's or column's total, the parent's (null: none). */
    public Object denominator(Plan.Value v, String rowKey, String colKey, Map<String, Figure> unused) {
        Node r = rowNodes.get(rowKey);
        Node c = colNodes.get(colKey);
        if (r == null || c == null || v.showAs() == null) {
            return null;
        }
        Plan.Value plain = new Plan.Value(v.label(), v.formula(), null);
        return switch (v.showAs()) {
            case "pct-grand" -> rowNodes.containsKey(Keys.ALL) && colNodes.containsKey(Keys.ALL) ? value(plain, rowNodes.get(Keys.ALL), colNodes.get(Keys.ALL)) : null;
            case "pct-row" -> colNodes.containsKey(Keys.ALL) ? value(plain, r, colNodes.get(Keys.ALL)) : null;
            case "pct-column" -> rowNodes.containsKey(Keys.ALL) ? value(plain, rowNodes.get(Keys.ALL), c) : null;
            case "pct-parent-row" -> r.parent == null ? null : value(plain, r.parent, c);
            default -> null;
        };
    }

    private String emptyNote(Plan.Value v) {
        List<Ast.Call> calls = Agg.calls(v.formula());
        return calls.isEmpty() ? "no values in the cell" : Measures.agg(calls.get(0)).emptyNote();
    }

    // ---- show-as

    private Object[] showAs(Plan.Value v, Node r, Node c, Object x, Map<String, Object> base, Map<String, Node> rowNodes,
            Map<String, Node> colNodes, List<Node> rows, List<Node> cols) {
        if (!(x instanceof Double d)) {
            return new Object[] {null, "a show-as needs a number"};
        }
        String code = v.showAs();
        String label = v.label();
        switch (code.contains(":") ? code.substring(0, code.indexOf(':')) : code) {
            case "pct-grand" -> {
                return share(d, base.get(Keys.ALL + "\u0000" + Keys.ALL + "\u0000" + label));
            }
            case "pct-row" -> {
                return share(d, base.get(r.key + "\u0000" + Keys.ALL + "\u0000" + label));
            }
            case "pct-column" -> {
                return share(d, base.get(Keys.ALL + "\u0000" + c.key + "\u0000" + label));
            }
            case "pct-parent-row" -> {
                if (r.parent == null) {
                    return new Object[] {1.0, null};
                }
                return share(d, base.get(r.parent.key + "\u0000" + c.key + "\u0000" + label));
            }
            case "rank", "ntile" -> {
                List<Double> sib = new ArrayList<>();
                for (Node s : siblings(r)) {
                    if (base.get(s.key + "\u0000" + c.key + "\u0000" + label) instanceof Double y && !s.others) {
                        sib.add(y);
                    }
                }
                if (r.others || r.parent == null) {
                    return new Object[] {null, "ranked among its siblings only"};
                }
                int above = 0;
                int same = 0;
                for (double y : sib) {
                    if (y > d) {
                        above++;
                    } else if (y == d) {
                        same++;
                    }
                }
                if ("rank".equals(code)) {
                    return new Object[] {(double) (above + 1), null};
                }
                int n = Integer.parseInt(code.substring(6));
                int below = sib.size() - above - same;
                double meanRank = below + (same + 1) / 2.0;                  // ascending, ties at their mean rank
                return new Object[] {Math.floor(meanRank * n / (sib.size() + 1)) + 1, null};
            }
            case "diff", "pct-change" -> {
                String[] parts = code.split(":", 3);
                int lag = Integer.parseInt(parts[1]);
                String dim = parts[2];
                boolean onRows = dim.equals(r.dim());
                Node n = onRows ? r : c;
                if (!dim.equals(n.dim())) {
                    return new Object[] {null, "the cell is not on a member of " + dim};
                }
                List<Node> sib = (onRows ? rows : cols).stream().filter(s -> s.parent == n.parent && dim.equals(s.dim()) && !s.others).toList();
                int at = sib.indexOf(n);
                if (at - lag < 0 || at - lag >= sib.size()) {
                    return new Object[] {null, "no member " + lag + " before it"};
                }
                Node prev = sib.get(at - lag);
                Object p = onRows ? base.get(prev.key + "\u0000" + c.key + "\u0000" + label) : base.get(r.key + "\u0000" + prev.key + "\u0000" + label);
                if (!(p instanceof Double pd)) {
                    return new Object[] {null, "the earlier member is empty"};
                }
                if ("diff".equals(parts[0])) {
                    return new Object[] {d - pd, null};
                }
                return pd == 0 ? new Object[] {null, "the earlier member is 0"} : new Object[] {(d - pd) / Math.abs(pd), null};
            }
            default -> {
                return new Object[] {null, "a show-as that is not known: " + code};
            }
        }
    }

    private static List<Node> siblings(Node n) {
        return n.parent == null ? List.of(n) : n.parent.children;
    }

    private static Object[] share(double d, Object total) {
        if (!(total instanceof Double t)) {
            return new Object[] {null, "the total is empty"};
        }
        if (t == 0) {
            return new Object[] {null, "the total is 0"};
        }
        return new Object[] {d / t, null};
    }
}
