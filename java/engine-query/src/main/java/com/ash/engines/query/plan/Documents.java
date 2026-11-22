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
package com.ash.engines.query.plan;

import com.ash.engines.query.expr.Ast;
import com.ash.engines.query.expr.Parser;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the three documents into one {@link Plan}: a pivot view ({@code view}), a model query ({@code query}) and a derived-kind
 * declaration ({@code derived}). The host has checked each against its JSON Schema; what the schema cannot say is refused here in
 * words ({@link IllegalArgumentException}, which the session turns into the {@code QRY-} code of the problem).
 */
public final class Documents {

    private Documents() {}

    /** A document asks for rows as they are: not this engine's ({@code QRY-4001}). */
    public static final class RowsAsTheyAre extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        RowsAsTheyAre() {
            super("rows as they are (detail: true) are read by the host's read path, not computed by the query engine");
        }
    }

    /** A pivot view. {@code snapshotDate}: the snapshot's date, the as-of date when the view gives none (null: none). */
    @SuppressWarnings("unchecked")
    public static Plan view(Map<String, Object> m, String snapshotDate) {
        String source = text(m.get("source"));
        Map<String, Ast> named = new LinkedHashMap<>();
        if (m.get("measures") instanceof Map<?, ?> nm) {
            nm.forEach((k, v) -> named.put(String.valueOf(k), Parser.parse(String.valueOf(v))));
        }
        Map<String, Object> cal = m.get("calendar") instanceof Map<?, ?> c ? (Map<String, Object>) c : Map.of();
        String asOf = text(m.get("asOf")) != null ? text(m.get("asOf")) : snapshotDate;
        Measures measures = new Measures(named, windows(asOf, null, cal), currency(m.get("currency")));
        List<Plan.Value> values = new ArrayList<>();
        Set<String> labels = new LinkedHashSet<>();
        for (Object o : (List<Object>) m.get("values")) {
            Plan.Value v = o instanceof Map<?, ?> vm
                    ? measures.value(String.valueOf(vm.get("measure")), text(vm.get("as")), text(vm.get("showAs")))
                    : measures.value(String.valueOf(o), null, null);
            checked(v);
            if (!labels.add(v.label())) {
                throw new IllegalArgumentException("two values are called '" + v.label() + "': name one with as");
            }
            values.add(v);
        }
        List<Plan.Dim> rows = dims(m.get("rows"));
        List<Plan.Dim> cols = dims(m.get("columns"));
        Set<String> seen = new LinkedHashSet<>();
        for (Plan.Dim d : concat(rows, cols)) {
            if (!seen.add(d.id())) {
                throw new IllegalArgumentException(d.id() + " is on the axes twice");
            }
        }
        Ast where = null;
        if (m.get("filters") instanceof Map<?, ?> fm) {
            for (Map.Entry<?, ?> e : fm.entrySet()) {
                Plan.Dim d = Plan.Dim.parse(String.valueOf(e.getKey()));
                List<Object> in = e.getValue() instanceof List<?> l ? (List<Object>) l
                        : e.getValue() instanceof Map<?, ?> f && f.get("in") instanceof List<?> l2 ? (List<Object>) l2 : null;
                List<Object> notIn = e.getValue() instanceof Map<?, ?> f && f.get("notIn") instanceof List<?> l3 ? (List<Object>) l3 : null;
                Ast field = d.grain() == null ? new Ast.Ref(d.field()) : null;
                if (field == null) {
                    throw new IllegalArgumentException("a filter on " + d.id() + ": filter the field " + d.field() + " with where");
                }
                if (in != null) {
                    where = Ast.and(where, new Ast.In(field, literals(in), false));
                }
                if (notIn != null && !notIn.isEmpty()) {
                    where = Ast.and(where, new Ast.In(field, literals(notIn), true));
                }
            }
        }
        if (text(m.get("where")) != null) {
            where = Ast.and(where, Parser.parse(text(m.get("where"))));
        }
        List<Plan.Expansion> exp = new ArrayList<>();
        if (m.get("expanded") instanceof List<?> el) {
            for (Object o : el) {
                Map<String, Object> e = (Map<String, Object>) o;
                List<List<String>> path = new ArrayList<>();
                for (Object pair : (List<Object>) e.get("path")) {
                    List<Object> pl = (List<Object>) pair;
                    path.add(List.of(String.valueOf(pl.get(0)), String.valueOf(pl.get(1))));
                }
                exp.add(new Plan.Expansion("columns".equals(e.get("axis")) ? "columns" : "rows", path, text(e.get("by"))));
            }
        }
        int er = 1;
        int ec = Math.max(1, cols.size());
        if (m.get("expandTo") instanceof Map<?, ?> et) {
            er = et.get("rows") instanceof Number n ? n.intValue() : er;
            ec = et.get("columns") instanceof Number n ? n.intValue() : ec;
        }
        Map<String, Plan.Sort> sort = new LinkedHashMap<>();
        if (m.get("sort") instanceof Map<?, ?> sm) {
            sm.forEach((k, v) -> {
                Map<String, Object> s = (Map<String, Object>) v;
                sort.put(String.valueOf(k), new Plan.Sort(text(s.get("by")), Boolean.TRUE.equals(s.get("desc"))));
            });
        }
        Map<String, Plan.Top> top = new LinkedHashMap<>();
        if (m.get("top") instanceof Map<?, ?> tm) {
            tm.forEach((k, v) -> {
                Map<String, Object> t = (Map<String, Object>) v;
                top.put(String.valueOf(k), new Plan.Top(((Number) t.get("n")).intValue(), text(t.get("by"))));
            });
        }
        boolean totals = !Boolean.FALSE.equals(m.get("totals"));
        Integer limit = m.get("limit") instanceof Number n ? n.intValue() : null;
        return new Plan(source, rows, List.of(), cols, values, where, exp, er, ec, sort, top, totals, false, List.of(), limit,
                calcGroups(m.get("calc-groups"), measures));
    }

    /** A model query: one flat level of rows, every level open, sorted and cut as one list. */
    @SuppressWarnings("unchecked")
    public static Plan query(Map<String, Object> m, String snapshotDate) {
        if (Boolean.TRUE.equals(m.get("detail"))) {
            throw new RowsAsTheyAre();
        }
        Map<String, Object> model = m.get("model") instanceof Map<?, ?> mm ? (Map<String, Object>) mm : Map.of();
        Map<String, Ast> named = new LinkedHashMap<>();
        if (model.get("measures") instanceof Map<?, ?> nm) {
            nm.forEach((k, v) -> named.put(String.valueOf(k), Parser.parse(String.valueOf(v))));
        }
        Map<String, Object> dates = m.get("dates") instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of();
        Map<String, Object> cal = model.get("calendar") instanceof Map<?, ?> c ? (Map<String, Object>) c : Map.of();
        String asOf = text(dates.get("to")) != null ? text(dates.get("to")) : snapshotDate;
        Measures measures = new Measures(named, windows(asOf, text(dates.get("from")), cal), currency(model.get("currency")));
        List<Plan.Value> values = new ArrayList<>();
        Set<String> labels = new LinkedHashSet<>();
        for (Object o : m.get("measures") instanceof List<?> l ? (List<Object>) l : List.of()) {
            Plan.Value v = measures.value(String.valueOf(o), null, null);
            checked(v);
            if (labels.add(v.label())) {
                values.add(v);
            }
        }
        if (values.isEmpty()) {
            values.add(measures.value("count()", "count", null));            // fields only: each combination once, with its rows
        }
        Ast where = null;
        for (Object o : m.get("filters") instanceof List<?> l ? (List<Object>) l : List.of()) {
            Map<String, Object> f = (Map<String, Object>) o;
            where = Ast.and(where, filter(String.valueOf(f.get("field")), String.valueOf(f.get("op")),
                    f.get("values") instanceof List<?> vl ? (List<Object>) vl : List.of()));
        }
        String dateField = text(dates.get("field"));
        if (dateField != null && text(dates.get("from")) != null) {
            where = Ast.and(where, new Ast.Binary(">=", new Ast.Ref(dateField), new Ast.Str(text(dates.get("from")))));
        }
        if (dateField != null && text(dates.get("to")) != null) {
            where = Ast.and(where, new Ast.Binary("<=", new Ast.Ref(dateField), new Ast.Str(text(dates.get("to")))));
        }
        List<Plan.Dim> rows = dims(m.get("fields"));
        List<Plan.Sort> order = new ArrayList<>();
        for (Object o : m.get("orderBy") instanceof List<?> l ? (List<Object>) l : List.of()) {
            Map<String, Object> s = (Map<String, Object>) o;
            order.add(new Plan.Sort(String.valueOf(s.get("by")), Boolean.TRUE.equals(s.get("desc"))));
        }
        Integer limit = m.get("limit") instanceof Number n ? n.intValue() : null;
        return new Plan(text(m.get("from")), rows, List.of(), List.of(), values, where, List.of(), Math.max(1, rows.size()), 1, Map.of(),
                Map.of(), Boolean.TRUE.equals(m.get("totals")), true, order, limit, calcGroups(model.get("calc-groups"), measures));
    }

    /** A derived-kind declaration: one grouping per {@code group-by} and per {@code also-group-by}; no totals. */
    @SuppressWarnings("unchecked")
    public static Plan derived(Map<String, Object> m) {
        Measures measures = new Measures(Map.of(), null, null);
        List<Plan.Value> values = new ArrayList<>();
        Ast id = text(m.get("id")) == null ? null : Parser.parse(text(m.get("id")));
        for (Map.Entry<String, Object> f : ((Map<String, Object>) m.get("fields")).entrySet()) {
            String spec = String.valueOf(f.getValue()).strip();
            String op = spec.contains(" ") ? spec.substring(0, spec.indexOf(' ')) : spec;
            String arg = spec.contains(" ") ? spec.substring(spec.indexOf(' ') + 1).strip() : null;
            String formula = switch (op) {
                case "count" -> "count()";
                case "sum", "avg", "median", "min", "max" -> op + "(" + need(arg, f.getKey(), op) + ")";
                case "distinct" -> "values(" + need(arg, f.getKey(), op) + ")";
                case "first" -> {
                    if (id == null) {
                        throw new IllegalArgumentException(f.getKey() + ": first needs the members' order, id: $.TRADE_ID");
                    }
                    yield "first(" + need(arg, f.getKey(), op) + ", " + id.el() + ")";
                }
                default -> throw new IllegalArgumentException(f.getKey() + ": " + op + " is not count, sum, avg, median, min, max, distinct or first");
            };
            values.add(new Plan.Value(f.getKey(), derivedFormula(formula, measures), null));
        }
        Ast where = text(m.get("where")) == null ? null : Parser.parse(text(m.get("where")));
        Ast groupBy = Parser.parse(String.valueOf(m.get("group-by")));
        Plan.Dim key = groupBy instanceof Ast.Ref r ? new Plan.Dim(r.name(), r.name(), null, null, null)     // a column: grouped at the source
                : new Plan.Dim("KEY", null, null, null, groupBy);
        List<Plan.Dim> also = new ArrayList<>();
        if (m.get("also-group-by") instanceof Map<?, ?> am) {
            am.forEach((k, v) -> also.add(new Plan.Dim(String.valueOf(k), null, null, null, Parser.parse(String.valueOf(v)))));
        }
        return new Plan(text(m.get("from")), List.of(key), also, List.of(), values, where, List.of(), 1, 1, Map.of(), Map.of(), false, false,
                List.of(), null, Map.of());
    }

    private static Ast derivedFormula(String formula, Measures measures) {
        Ast f = Parser.parse(formula);
        if (f instanceof Ast.Call c && ("values".equals(c.name()) || "first".equals(c.name()))) {
            return f;                                                // derived-only aggregates, read by DerivedAggs
        }
        return measures.rewrite(f);
    }

    private static String need(String arg, String field, String op) {
        if (arg == null || arg.isBlank()) {
            throw new IllegalArgumentException(field + ": " + op + " needs a value, " + op + " $.x");
        }
        return arg;
    }

    /** A BI filter as a condition. */
    static Ast filter(String field, String op, List<Object> values) {
        Ast f = new Ast.Ref(field);
        Ast v0 = values.isEmpty() ? new Ast.Null() : literal(values.get(0));
        return switch (op) {
            case "eq" -> new Ast.Binary("==", f, v0);
            case "ne" -> new Ast.Binary("!=", f, v0);
            case "in" -> new Ast.In(f, literals(values), false);
            case "not-in" -> new Ast.In(f, literals(values), true);
            case "lt" -> new Ast.Binary("<", f, v0);
            case "le" -> new Ast.Binary("<=", f, v0);
            case "gt" -> new Ast.Binary(">", f, v0);
            case "ge" -> new Ast.Binary(">=", f, v0);
            case "between" -> {
                if (values.size() != 2) {
                    throw new IllegalArgumentException("between takes two values: [low, high]");
                }
                yield new Ast.Binary("&&", new Ast.Binary(">=", f, v0), new Ast.Binary("<=", f, literal(values.get(1))));
            }
            case "contains" -> new Ast.Call("contains", List.of(f, v0));
            case "starts-with" -> new Ast.Call("starts_with", List.of(f, v0));
            case "is-null" -> new Ast.Binary("==", f, new Ast.Null());
            case "not-null" -> new Ast.Binary("!=", f, new Ast.Null());
            default -> throw new IllegalArgumentException("a filter's op is eq, ne, in, not-in, lt, le, gt, ge, between, contains, starts-with, "
                    + "is-null or not-null, not " + op);
        };
    }

    private static Ast.ListOf literals(List<Object> values) {
        return new Ast.ListOf(values.stream().map(Documents::literal).toList());
    }

    private static Ast literal(Object v) {
        if (v == null) {
            return new Ast.Null();
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return new Ast.Num(d, d == Math.rint(d) && !(v instanceof Double));
        }
        if (v instanceof Boolean b) {
            return new Ast.Bool(b);
        }
        return new Ast.Str(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Ast>> calcGroups(Object o, Measures measures) {
        Map<String, Map<String, Ast>> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                Map<String, Ast> items = new LinkedHashMap<>();
                Object it = v instanceof Map<?, ?> g ? g.get("items") : null;
                if (it instanceof Map<?, ?> im) {
                    im.forEach((ik, iv) -> items.put(String.valueOf(ik),
                            measures.item(Parser.parse(String.valueOf(iv).replace("${measure}", "SELECTED")))));
                }
                out.put(String.valueOf(k), items);
            });
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static TimeWindows windows(String asOf, String from, Map<String, Object> cal) {
        if (asOf == null) {
            return null;
        }
        Set<LocalDate> holidays = new LinkedHashSet<>();
        if (cal.get("holidays") instanceof List<?> hl) {
            hl.forEach(h -> holidays.add(LocalDate.parse(String.valueOf(h))));
        }
        int fy = cal.get("fiscal-year-start") instanceof Number n ? n.intValue() : 1;
        return new TimeWindows(LocalDate.parse(asOf.substring(0, 10)), from == null ? null : LocalDate.parse(from.substring(0, 10)), holidays, fy);
    }

    @SuppressWarnings("unchecked")
    private static Measures.Currency currency(Object o) {
        if (!(o instanceof Map<?, ?> m) || m.get("report") == null) {
            return null;
        }
        Map<String, Double> rates = new LinkedHashMap<>();
        if (m.get("rates") instanceof Map<?, ?> rm) {
            rm.forEach((k, v) -> rates.put(String.valueOf(k), ((Number) v).doubleValue()));
        }
        Set<String> money = new LinkedHashSet<>();
        if (m.get("money") instanceof List<?> ml) {
            ml.forEach(x -> money.add(String.valueOf(x)));
        }
        String field = m.get("field") == null ? "CCY" : String.valueOf(m.get("field"));
        return new Measures.Currency(String.valueOf(m.get("report")), field, money, rates);
    }

    /** A value's formula reads fields only inside aggregates, and calls only known functions. */
    static void checked(Plan.Value v) {
        Measures.check(v.formula());
        outside(v.formula(), false);
    }

    private static void outside(Ast a, boolean inAgg) {
        if (a instanceof Ast.Ref r && !inAgg && !"SELECTED".equals(r.name())) {
            throw new IllegalArgumentException(r.name() + " is a field outside an aggregate: sum(" + r.name() + "), or a measure the document names");
        }
        if (a instanceof Ast.Call c) {
            boolean agg = inAgg || Agg.Fn.named(c.name()) != null;
            c.args().forEach(x -> outside(x, agg));
            c.named().forEach((k, x) -> outside(x, agg || "where".equals(k) || "ignore".equals(k)));
            return;
        }
        switch (a) {
            case Ast.Binary b -> {
                outside(b.left(), inAgg);
                outside(b.right(), inAgg);
            }
            case Ast.Unary u -> outside(u.operand(), inAgg);
            case Ast.Ternary t -> {
                outside(t.condition(), inAgg);
                outside(t.then(), inAgg);
                outside(t.otherwise(), inAgg);
            }
            case Ast.In in -> {
                outside(in.value(), inAgg);
                outside(in.list(), inAgg);
            }
            case Ast.ListOf l -> l.items().forEach(x -> outside(x, inAgg));
            default -> { }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Plan.Dim> dims(Object o) {
        List<Plan.Dim> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                out.add(Plan.Dim.parse(String.valueOf(x)));
            }
        }
        return out;
    }

    private static List<Plan.Dim> concat(List<Plan.Dim> a, List<Plan.Dim> b) {
        List<Plan.Dim> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static String text(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o).strip();
    }
}
