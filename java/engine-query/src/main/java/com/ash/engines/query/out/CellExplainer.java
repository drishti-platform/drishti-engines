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

import com.ash.engine.Cell;
import com.ash.engine.Control;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.Explanation;
import com.ash.engine.Filter;
import com.ash.engine.InputTable;
import com.ash.engine.Step;
import com.ash.engine.Steps;
import com.ash.engines.query.exec.Executor;
import com.ash.engines.query.expr.Ast;
import com.ash.engines.query.expr.Eval;
import com.ash.engines.query.plan.Agg;
import com.ash.engines.query.plan.Plan;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explains a figure of {@code cells} from its own formula: one step per aggregate it reads (the cell's rows by value, the
 * {@code explain-rows} largest shown and the rest as one subtotal, with a Rachana-EL formula over them), then the formula over those
 * steps, then the show-as. Holistic aggregates and the rank-like show-as are one opaque step each, naming the workbook cell that
 * holds them (counted by the kit; draft 1).
 */
public final class CellExplainer {

    private final Plan plan;
    private final Executor executor;
    private final Answering answering;
    private final Answering.Built built;
    private final int shown;

    public CellExplainer(Plan plan, Executor executor, Answering answering, Answering.Built built, int shown) {
        this.plan = plan;
        this.executor = executor;
        this.answering = answering;
        this.built = built;
        this.shown = shown;
    }

    public Explanation explain(Cell cell, InputTable table, Filter visibility, Control control) throws EngineException {
        String row = cell.key().get("ROW");
        String col = cell.key().get("COLUMN");
        String label = cell.key().get("VALUE");
        Answering.Figure f = row == null || col == null || label == null ? null : built.cell(row, col, label);
        Plan.Value v = plan.values().stream().filter(x -> x.label().equals(label)).findFirst().orElse(null);
        Map.Entry<Ast, List<Answering.AggAt>> at = v == null || f == null ? null : answering.aggregatesOf(v, row, col);
        if (at == null) {
            throw new EngineException("QRY-3002", Kind.REFUSED, "the cell " + row + " / " + col + " / " + label + " is not in this request's answer");
        }
        List<Step> steps = new ArrayList<>();
        int i = 0;
        for (Answering.AggAt a : at.getValue()) {
            steps.add(aggregateStep("a" + i++, a, table, visibility, control));
        }
        Ast formula = at.getKey();
        boolean single = formula instanceof Ast.Call c && Agg.Fn.named(c.name()) != null;
        String last = "a0";
        if (!single) {
            Ast over = replace(formula, new int[] {0});
            Object value = answering.valueAt(v, row, col);
            steps.add(Steps.of("value", label + ": its formula over the aggregates above", over.el(), Map.of(), value));
            last = "value";
        }
        if (v.showAs() != null) {
            Object total = answering.denominator(v, row, col, built.cells());
            if (total != null) {
                steps.add(Steps.of("shown", label + " shown as " + v.showAs() + ": the figure over its total", "step." + last + " / $.total",
                        Map.of("total", total), f.number()));
            } else {
                steps.add(Steps.opaque("shown", label + " shown as " + v.showAs(), "a rank, bucket or difference among the answer's cells",
                        "Main!D2", f.number()));
            }
        }
        return new Explanation(cell, f.number() != null ? f.number() : f.text(), steps);
    }

    private Step aggregateStep(String name, Answering.AggAt a, InputTable table, Filter visibility, Control control) throws EngineException {
        Agg g = a.agg();
        String words = g.key() + " over the cell's rows" + (a.coords().isEmpty() ? "" : " (" + a.coords() + ")");
        if (!Set.of(Agg.Fn.SUM, Agg.Fn.COUNT, Agg.Fn.MIN, Agg.Fn.MAX, Agg.Fn.AVG).contains(g.fn())) {
            return Steps.opaque(name, words, g.fn().name().toLowerCase(java.util.Locale.ROOT) + " is recomputed from every value of the cell in the workbook",
                    "Main!D2", a.figure());
        }
        Set<String> fields = new LinkedHashSet<>();
        g.fields(fields);
        List<Map<String, Object>> rows = executor.rows(table, visibility, a.coords(), a.notInDim(), a.notIn(), g.where(), new ArrayList<>(fields),
                Long.MAX_VALUE, control);
        List<Double> values = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Object x = g.arg() == null ? (Object) 1.0 : new Eval(r::get, c -> null).eval(g.arg());
            double d = Eval.number(x);
            if (x != null && !Double.isNaN(d)) {
                values.add(d);
            }
        }
        Comparator<Double> order = switch (g.fn()) {
            case MIN -> Comparator.naturalOrder();
            case MAX -> Comparator.reverseOrder();
            default -> Comparator.<Double, Double>comparing(Math::abs).reversed().thenComparing(Comparator.naturalOrder());
        };
        values.sort(order);
        List<Map<String, Object>> list = new ArrayList<>();
        for (double d : values.subList(0, Math.min(shown, values.size()))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("v", d);
            list.add(m);
        }
        BigDecimal rest = BigDecimal.ZERO;
        for (double d : values.subList(Math.min(shown, values.size()), values.size())) {
            rest = rest.add(new BigDecimal(d));
        }
        long restCount = Math.max(0, values.size() - shown);
        String more = restCount > 0 ? "; " + list.size() + " of " + values.size() + " values shown, the rest as one subtotal" : "";
        Map<String, Object> given = new LinkedHashMap<>();
        given.put("rows", list);
        return switch (g.fn()) {
            case SUM -> {
                given.put("rest", rest.doubleValue());
                yield Steps.of(name, words + more, "sum($.rows, 'v') + $.rest", given, a.figure());
            }
            case COUNT -> {
                given.put("rest", restCount);
                yield Steps.of(name, words + more, "count($.rows) + $.rest", given, a.figure());
            }
            case MIN -> Steps.of(name, words + (restCount > 0 ? "; the " + list.size() + " smallest values shown" : ""), "min($.rows, 'v')", given, a.figure());
            case MAX -> Steps.of(name, words + (restCount > 0 ? "; the " + list.size() + " largest values shown" : ""), "max($.rows, 'v')", given, a.figure());
            default -> {
                given.put("rest", rest.doubleValue());
                given.put("restCount", restCount);
                yield Steps.of(name, words + more, "(sum($.rows, 'v') + $.rest) / (count($.rows) + $.restCount)", given, a.figure());
            }
        };
    }

    /** The formula with each aggregate (and each {@code calc} around one) replaced by its step, in the order the aggregates were read. */
    private static Ast replace(Ast f, int[] next) {
        if (f instanceof Ast.Call c && "calc".equals(c.name())) {
            return replace(c.args().get(0), next);
        }
        if (f instanceof Ast.Call c && Agg.Fn.named(c.name()) != null) {
            return new Ast.Ref("step.a" + next[0]++);
        }
        return switch (f) {
            case Ast.Call c -> {
                List<Ast> args = new ArrayList<>();
                for (Ast x : c.args()) {
                    args.add(replace(x, next));
                }
                Map<String, Ast> named = new LinkedHashMap<>();
                for (Map.Entry<String, Ast> e : c.named().entrySet()) {
                    named.put(e.getKey(), replace(e.getValue(), next));
                }
                yield new Ast.Call(c.name(), args, named);
            }
            case Ast.Binary b -> {
                Ast l = replace(b.left(), next);
                yield new Ast.Binary(b.op(), l, replace(b.right(), next));
            }
            case Ast.Unary u -> new Ast.Unary(u.op(), replace(u.operand(), next));
            case Ast.Ternary t -> {
                Ast c = replace(t.condition(), next);
                Ast a = replace(t.then(), next);
                yield new Ast.Ternary(c, a, replace(t.otherwise(), next));
            }
            case Ast.In in -> {
                Ast x = replace(in.value(), next);
                yield new Ast.In(x, replace(in.list(), next), in.negated());
            }
            case Ast.ListOf l -> {
                List<Ast> items = new ArrayList<>();
                for (Ast x : l.items()) {
                    items.add(replace(x, next));
                }
                yield new Ast.ListOf(items);
            }
            default -> f;
        };
    }
}
