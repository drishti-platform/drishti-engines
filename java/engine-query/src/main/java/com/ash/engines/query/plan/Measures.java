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
import java.util.TreeSet;

/**
 * Rewrites a value's formula into the plain form the planner reads, one rule at a time:
 *
 * <ol>
 * <li>a named measure ({@code Notional}, a model's measure) is replaced by its formula, recursively; a measure that names itself is
 * refused;</li>
 * <li>a show-as written around the formula ({@code pct_of_total(sum(X))}) is taken off and returned as the show-as code;</li>
 * <li>a time function ({@code ytd(agg, DATE)}) becomes {@code calc(agg, where = DATE >= 'lo' && DATE <= 'hi')} at its window;</li>
 * <li>{@code share_of(agg, over = [D])} becomes {@code agg / calc(agg, ignore = [D])};</li>
 * <li>{@code calc(agg, where = c)} pushes {@code c} into every aggregate inside, so it becomes the aggregate's own condition (a
 * {@code calc} that also ignores dimensions keeps its {@code ignore});</li>
 * <li>a money field inside an aggregate is converted row by row: {@code X} becomes {@code X * (CCY == 'EUR' ? 1.08 : ...)}, a row
 * without a rate giving null (left out).</li>
 * </ol>
 *
 * Every rule writes Rachana-EL, so what the source is asked and what an explanation shows are the same text.
 */
public final class Measures {

    /** The show-as functions a value may be written in, with their codes. */
    static final Map<String, String> SHOW_AS = Map.of("pct_of_total", "pct-grand", "pct_of_row", "pct-row", "pct_of_column", "pct-column",
            "pct_of_parent", "pct-parent-row", "rank", "rank");

    private final Map<String, Ast> named;
    private final TimeWindows windows;
    private final Currency currency;

    /**
     * Money converted at given rates.
     *
     * @param report the reporting currency
     * @param field the field holding a row's currency
     * @param money the money fields
     * @param rates currency to its rate into {@code report}
     */
    public record Currency(String report, String field, Set<String> money, Map<String, Double> rates) {
        public Currency {
            money = Set.copyOf(money);
            rates = Map.copyOf(rates);
        }
    }

    /** @param named the named measures' formulas; @param windows the time windows (null: none asked); @param currency or null */
    public Measures(Map<String, Ast> named, TimeWindows windows, Currency currency) {
        this.named = Map.copyOf(named);
        this.windows = windows;
        this.currency = currency;
    }

    /** A value read: its label, its rewritten formula and its show-as (the one given, else the one written around it). */
    public Plan.Value value(String text, String as, String showAs) {
        Ast f = Parser(text);
        String sa = showAs;
        if (f instanceof Ast.Call c && (SHOW_AS.containsKey(c.name()) || "ntile".equals(c.name())) && !c.args().isEmpty()) {
            if (sa != null) {
                throw new IllegalArgumentException(text + " is already a share, rank or window: leave its show-as out");
            }
            if ("ntile".equals(c.name())) {
                if (c.args().size() != 2 || !(c.args().get(1) instanceof Ast.Num n) || n.value() < 1) {
                    throw new IllegalArgumentException("ntile takes a value and a number of buckets: ntile(sum(X), 4)");
                }
                sa = "ntile:" + (long) n.value();
            } else {
                sa = SHOW_AS.get(c.name());
            }
            f = c.args().get(0);
        }
        return new Plan.Value(as == null || as.isBlank() ? text.strip() : as, rewrite(f), sa);
    }

    private static Ast Parser(String text) {
        return com.ash.engines.query.expr.Parser.parse(text);
    }

    /** The formula with every rule applied. */
    public Ast rewrite(Ast f) {
        Ast a = expand(f, new LinkedHashSet<>());
        a = a.map(this::time);
        a = a.map(Measures::shares);
        a = pushWhere(a, null);
        return currency == null ? a : a.map(this::money);
    }

    private Ast expand(Ast f, Set<String> seen) {
        return f.map(x -> {
            if (x instanceof Ast.Ref r && named.containsKey(r.name())) {
                if (!seen.add(r.name())) {
                    throw new IllegalArgumentException("the measure " + r.name() + " names itself (through " + String.join(", ", seen) + ")");
                }
                try {
                    return expand(named.get(r.name()), seen);
                } finally {
                    seen.remove(r.name());
                }
            }
            return x;
        });
    }

    private Ast time(Ast x) {
        if (!(x instanceof Ast.Call c) || !TimeWindows.FUNCTIONS.contains(c.name())) {
            return x;
        }
        if (c.args().size() != 2 || !(c.args().get(1) instanceof Ast.Ref date)) {
            throw new IllegalArgumentException(c.name() + " takes an aggregate and a date field: " + c.name() + "(sum(X), TRADE_DATE)");
        }
        if (windows == null) {
            throw new IllegalArgumentException(c.name() + " needs an as-of date: the snapshot has no date and the document gives none");
        }
        TimeWindows.Window w = windows.of(c.name());
        Ast cond = new Ast.Binary("&&", new Ast.Binary(">=", date, new Ast.Str(w.lo().toString())),
                new Ast.Binary("<=", date, new Ast.Str(w.hi().toString())));
        return new Ast.Call("calc", List.of(c.args().get(0)), Map.of("where", cond));
    }

    private static Ast shares(Ast x) {
        if (!(x instanceof Ast.Call c) || !"share_of".equals(c.name())) {
            return x;
        }
        if (c.args().size() != 1) {
            throw new IllegalArgumentException("share_of takes one aggregate and over = [dimensions]: share_of(sum(X), over = [DESK])");
        }
        Ast over = c.arg("over") == null ? new Ast.ListOf(List.of()) : c.arg("over");
        return new Ast.Binary("/", c.args().get(0), new Ast.Call("calc", List.of(c.args().get(0)), Map.of("ignore", over)));
    }

    /** Pushes each {@code calc}'s {@code where} into the aggregates inside it. */
    private static Ast pushWhere(Ast a, Ast where) {
        if (a instanceof Ast.Call c && "calc".equals(c.name())) {
            if (c.args().size() != 1) {
                throw new IllegalArgumentException("calc takes one aggregate and where =, ignore =: calc(sum(X), where = DESK == 'FX')");
            }
            for (String k : c.named().keySet()) {
                if (!Set.of("where", "ignore").contains(k)) {
                    throw new IllegalArgumentException("calc takes where = and ignore =, not " + k + " =");
                }
            }
            Ast inner = pushWhere(c.args().get(0), Ast.and(where, c.arg("where")));
            if (c.arg("ignore") == null) {
                return inner;
            }
            return new Ast.Call("calc", List.of(inner), Map.of("ignore", c.arg("ignore")));
        }
        if (a instanceof Ast.Call c && Agg.Fn.named(c.name()) != null) {
            if (where == null) {
                return a;
            }
            Map<String, Ast> n = new LinkedHashMap<>(c.named());
            n.put("where", Ast.and(where, c.arg("where")));
            return new Ast.Call(c.name(), c.args(), n);
        }
        return switch (a) {
            case Ast.Call c -> {
                Map<String, Ast> n = new LinkedHashMap<>();
                c.named().forEach((k, v) -> n.put(k, pushWhere(v, where)));
                yield new Ast.Call(c.name(), c.args().stream().map(x -> pushWhere(x, where)).toList(), n);
            }
            case Ast.Binary b -> new Ast.Binary(b.op(), pushWhere(b.left(), where), pushWhere(b.right(), where));
            case Ast.Unary u -> new Ast.Unary(u.op(), pushWhere(u.operand(), where));
            case Ast.Ternary t -> new Ast.Ternary(pushWhere(t.condition(), where), pushWhere(t.then(), where), pushWhere(t.otherwise(), where));
            case Ast.In in -> new Ast.In(pushWhere(in.value(), where), pushWhere(in.list(), where), in.negated());
            case Ast.ListOf l -> new Ast.ListOf(l.items().stream().map(x -> pushWhere(x, where)).toList());
            default -> a;
        };
    }

    private Ast money(Ast x) {
        if (!(x instanceof Ast.Call c) || Agg.Fn.named(c.name()) == null || c.args().isEmpty()) {
            return x;
        }
        List<Ast> args = new ArrayList<>(c.args());
        args.set(0, args.get(0).map(y -> y instanceof Ast.Ref r && currency.money().contains(r.name()) ? converted(r) : y));
        return new Ast.Call(c.name(), args, c.named());
    }

    private Ast converted(Ast.Ref r) {
        Ast rate = new Ast.Null();
        List<String> ccys = new ArrayList<>(new TreeSet<>(currency.rates().keySet()));
        ccys.remove(currency.report());
        java.util.Collections.reverse(ccys);
        for (String ccy : ccys) {
            rate = new Ast.Ternary(new Ast.Binary("==", new Ast.Ref(currency.field()), new Ast.Str(ccy)), new Ast.Num(currency.rates().get(ccy), false), rate);
        }
        rate = new Ast.Ternary(new Ast.Binary("==", new Ast.Ref(currency.field()), new Ast.Str(currency.report())), new Ast.Num(1, true), rate);
        return new Ast.Binary("*", r, rate);
    }

    /** The aggregate of a call, its condition from its {@code where}. */
    public static Agg agg(Ast.Call c) {
        return Agg.of(c, null);
    }

    /**
     * A calculation item's template read: named measures, time functions and shares rewritten, its {@code calc(..., where = ...)} left
     * in place until {@code SELECTED} is known ({@link #selected}).
     */
    public Ast item(Ast template) {
        Ast a = expand(template, new LinkedHashSet<>());
        a = a.map(this::time);
        return a.map(Measures::shares);
    }

    /**
     * A calculation item's template with {@code SELECTED} (or {@code ${measure}}) replaced by the value's (already rewritten) formula,
     * then each {@code calc}'s condition pushed into the aggregates it now holds.
     */
    public static Ast selected(Ast item, Ast formula) {
        return pushWhere(item.map(x -> x instanceof Ast.Ref r && "SELECTED".equals(r.name()) ? formula : x), null);
    }

    /** The dimension lists the formula's {@code calc(..., ignore = [...])} calls ignore. */
    public static List<Set<String>> ignores(Ast formula) {
        List<Set<String>> out = new ArrayList<>();
        formula.walk(a -> {
            if (a instanceof Ast.Call c && "calc".equals(c.name()) && c.arg("ignore") != null) {
                out.add(ignored(c));
            }
        });
        return out;
    }

    /** The dimension ids a {@code calc} ignores: {@code ignore = [DESK, 'REGION']} or one name. */
    public static Set<String> ignored(Ast.Call c) {
        Ast i = c.arg("ignore");
        Set<String> out = new TreeSet<>();
        List<Ast> items = i instanceof Ast.ListOf l ? l.items() : List.of(i);
        for (Ast x : items) {
            if (x instanceof Ast.Ref r) {
                out.add(r.name());
            } else if (x instanceof Ast.Str s) {
                out.add(s.value());
            } else {
                throw new IllegalArgumentException("ignore names dimensions: ignore = [DESK], not " + x.el());
            }
        }
        return out;
    }

    /** The scalar functions and aggregates a formula calls that are neither: refused in words. */
    public static void check(Ast formula) {
        formula.walk(a -> {
            if (a instanceof Ast.Call c && !Eval.SCALARS.contains(c.name()) && Agg.Fn.named(c.name()) == null && !"calc".equals(c.name())) {
                throw new UnknownFunction(c.name());
            }
        });
        Agg.calls(formula);
    }

    /** A function that is not known ({@code QRY-1004}). */
    public static final class UnknownFunction extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        UnknownFunction(String name) {
            super(name + " is not a function a value may call");
        }
    }
}
