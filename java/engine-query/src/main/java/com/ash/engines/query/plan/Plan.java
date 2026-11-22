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
import com.ash.engines.query.expr.Eval;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * One question, whichever document asked it: the table, the axes, the values (formulas already rewritten: named measures expanded,
 * time windows and money conversion applied), the condition every row must meet, which nodes are open, sort, top N and totals.
 *
 * @param table the open table's name
 * @param rows the row dimensions, outermost first
 * @param alsoRows further first-level row dimensions, each a grouping of its own (a derived kind's {@code also-group-by})
 * @param columns the column dimensions
 * @param values the values, in the order asked
 * @param where the condition every row meets (filters, dates, {@code where}), or null
 * @param expanded nodes opened beyond {@code expandRows} / {@code expandColumns}
 * @param expandRows every row node open down to this depth
 * @param expandColumns every column node open down to this depth
 * @param sort per dimension id: by a value's label or by the member
 * @param top per dimension id: its top N by a value, the rest one Others member
 * @param totals false: no grand total and no subtotals
 * @param flat true: the row dimensions are one level (a model query's rows), sorted and cut as one list
 * @param order a flat plan's order: value labels or dimension ids, each descending or not
 * @param limit a flat plan's row limit (null: none)
 * @param calcGroups calculation groups by name: item name to template (with {@code SELECTED})
 */
public record Plan(String table, List<Dim> rows, List<Dim> alsoRows, List<Dim> columns, List<Value> values, Ast where,
        List<Expansion> expanded, int expandRows, int expandColumns, Map<String, Sort> sort, Map<String, Top> top, boolean totals,
        boolean flat, List<Sort> order, Integer limit, Map<String, Map<String, Ast>> calcGroups) {

    public Plan {
        rows = List.copyOf(rows);
        alsoRows = List.copyOf(alsoRows);
        columns = List.copyOf(columns);
        values = List.copyOf(values);
        expanded = List.copyOf(expanded);
        sort = Map.copyOf(sort);
        top = Map.copyOf(top);
        order = List.copyOf(order);
        calcGroups = Map.copyOf(calcGroups);
    }

    /** The member text of a null value. */
    public static final String BLANK = "(blank)";
    /** The member text of the Others member of a top N. */
    public static final String OTHERS = "(others)";

    /**
     * A dimension: a field, a field at a date grain, a calculation group, or (a derived kind's grouping) an expression.
     *
     * @param id how coordinates name it ({@code DESK}, {@code TRADE_DATE:month}, {@code calc:Time}, a derived grouping's name)
     */
    public record Dim(String id, String field, String grain, String calcGroup, Ast expr) {

        /** A dimension as a view or a query writes it. */
        public static Dim parse(String text) {
            String t = text.strip();
            if (t.startsWith("calc:")) {
                return new Dim(t, null, null, t.substring(5).strip(), null);
            }
            int c = t.lastIndexOf(':');
            if (c > 0) {
                String grain = t.substring(c + 1);
                if (!Set.of("year", "quarter", "month", "week", "day").contains(grain)) {
                    throw new IllegalArgumentException(t + ": a date grain is year, quarter, month, week or day, not " + grain);
                }
                return new Dim(t, t.substring(0, c), grain, null, null);
            }
            return new Dim(t, t, null, null, null);
        }

        /** A plain column the source can group by. */
        public boolean column() {
            return field != null && grain == null && calcGroup == null && expr == null && id.equals(field);
        }

        /** A calculation group: its members are the group's items, not values of a field. */
        public boolean calc() {
            return calcGroup != null;
        }

        /** The member of a row (a calculation group has none). */
        public String member(Function<String, Object> row) {
            Object v = expr != null ? new Eval(row, c -> {
                throw new IllegalArgumentException("an aggregate in a grouping: " + c.el());
            }).eval(expr) : row.apply(field);
            if (v == null) {
                return BLANK;
            }
            String s = Eval.text(Eval.plain(v));
            return grain == null ? s : grain(s, grain);
        }

        /** The fields it reads. */
        public void fields(Set<String> into) {
            if (field != null) {
                into.add(field);
            }
            if (expr != null) {
                expr.walk(a -> {
                    if (a instanceof Ast.Ref r) {
                        into.add(r.name());
                    }
                });
            }
        }

        /** An ISO date at a grain: {@code 2026}, {@code 2026-Q2}, {@code 2026-05}, {@code 2026-W19}, {@code 2026-05-07}. */
        static String grain(String iso, String grain) {
            if (iso.length() < 10) {
                return BLANK;
            }
            return switch (grain) {
                case "year" -> iso.substring(0, 4);
                case "quarter" -> iso.substring(0, 4) + "-Q" + ((Integer.parseInt(iso.substring(5, 7)) - 1) / 3 + 1);
                case "month" -> iso.substring(0, 7);
                case "week" -> {
                    java.time.LocalDate d = java.time.LocalDate.parse(iso.substring(0, 10));
                    int w = d.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                    int y = d.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR);
                    yield y + "-W" + (w < 10 ? "0" : "") + w;
                }
                default -> iso.substring(0, 10);
            };
        }
    }

    /**
     * A value of the answer.
     *
     * @param label its name in the answer ({@code as}, else the text as written)
     * @param formula its formula, rewritten
     * @param showAs a show-as code, or null
     */
    public record Value(String label, Ast formula, String showAs) {}

    /** An open node: its path of (dimension id, member) pairs from the top, and the dimension its children are broken down by. */
    public record Expansion(String axis, List<List<String>> path, String by) {
        public Expansion {
            path = path.stream().map(List::copyOf).toList();
        }
    }

    /** By a value's label, or by the member ({@code by} null or {@code label}). */
    public record Sort(String by, boolean desc) {}

    /** The top {@code n} members by a value's label ({@code by} null: the first value). */
    public record Top(int n, String by) {}

    /** Every aggregate the plan's values read, deduplicated, in the order first met (calculation groups' variants included). */
    public List<Agg> aggregates() {
        Map<String, Agg> out = new LinkedHashMap<>();
        for (Ast f : formulas()) {
            for (Ast.Call c : Agg.calls(f)) {
                Agg a = Measures.agg(c);
                out.putIfAbsent(a.key(), a);
            }
        }
        return new ArrayList<>(out.values());
    }

    /** Every formula a cell may evaluate: each value, and each value under each calculation group's items on an axis. */
    public List<Ast> formulas() {
        List<Ast> out = new ArrayList<>();
        for (Value v : values) {
            out.add(v.formula());
            for (Dim d : axisDims()) {
                if (d.calc()) {
                    for (Ast item : calcGroups.getOrDefault(d.calcGroup(), Map.of()).values()) {
                        out.add(Measures.selected(item, v.formula()));
                    }
                }
            }
        }
        return out;
    }

    /** The dimensions on both axes, rows first. */
    public List<Dim> axisDims() {
        List<Dim> all = new ArrayList<>(rows);
        all.addAll(alsoRows);
        all.addAll(columns);
        return all;
    }

    /** Every field the plan reads (dimensions, aggregates, the condition). */
    public Set<String> fields() {
        Set<String> out = new LinkedHashSet<>();
        axisDims().forEach(d -> d.fields(out));
        aggregates().forEach(a -> a.fields(out));
        if (where != null) {
            where.walk(a -> {
                if (a instanceof Ast.Ref r) {
                    out.add(r.name());
                }
            });
        }
        return out;
    }

    /** True when every value is made of push-down parts. */
    public boolean mergeable() {
        return aggregates().stream().allMatch(a -> a.fn().mergeable);
    }

    /** The dimension of an id on either axis, or null. */
    public Dim dim(String id) {
        for (Dim d : axisDims()) {
            if (d.id().equals(id)) {
                return d;
            }
        }
        return null;
    }
}
