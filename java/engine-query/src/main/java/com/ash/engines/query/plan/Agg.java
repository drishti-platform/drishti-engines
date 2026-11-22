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

import com.ash.engine.Aggregate;
import com.ash.engines.query.expr.Ast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One aggregate of a plan: a function over a row expression, with its own condition ({@code calc(agg, where=...)}, a time window).
 * Two spellings of one aggregate are one {@code Agg} ({@link #key()} is canonical), so a formula that asks {@code sum(PNL)} twice, or
 * two values that share it, read it once.
 *
 * <p>A mergeable aggregate is made of {@link Part}s, each one of the closed push-down functions (SUM, COUNT, MIN, MAX): the source
 * computes the parts when it offers a grouped scan, the engine computes the same parts from a scan otherwise, and the figure is
 * finished from the parts on both paths. A holistic aggregate has no parts: it is computed from a scan only.
 *
 * @param fn the function
 * @param arg its row expression (null for {@code count()})
 * @param arg2 the second argument: the weight of {@code wavg}, the parent of the {@code *_once} functions, the id of {@code first}
 * @param p the fraction of {@code percentile}
 * @param where the aggregate's own condition over a row, or null
 */
public record Agg(Fn fn, Ast arg, Ast arg2, double p, Ast where) {

    /** The functions an aggregate may be, by the name a formula writes (aliases in {@link #of}). */
    public enum Fn {
        SUM(true), COUNT(true), MIN(true), MAX(true), AVG(true), WAVG(true), VARIANCE(true), VARIANCE_POP(true), STDDEV(true),
        STDDEV_POP(true), SUM_ONCE(false), COUNT_ONCE(false), AVG_ONCE(false), MEDIAN(false), PERCENTILE(false), DISTINCT(false),
        MODE(false), STRING_AGG(false), VALUES(false), FIRST(false);

        /** True: made of push-down parts. */
        public final boolean mergeable;

        Fn(boolean mergeable) {
            this.mergeable = mergeable;
        }

        /** The function a formula's call names, or null when the name is not an aggregate. */
        public static Fn named(String name) {
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "sum" -> SUM;
                case "count" -> COUNT;
                case "min" -> MIN;
                case "max" -> MAX;
                case "avg", "mean" -> AVG;
                case "wavg" -> WAVG;
                case "variance", "var_samp", "var" -> VARIANCE;
                case "variance_pop", "var_pop" -> VARIANCE_POP;
                case "stddev", "stddev_samp", "stdev" -> STDDEV;
                case "stddev_pop" -> STDDEV_POP;
                case "sum_once" -> SUM_ONCE;
                case "count_once" -> COUNT_ONCE;
                case "avg_once" -> AVG_ONCE;
                case "median" -> MEDIAN;
                case "percentile" -> PERCENTILE;
                case "distinct", "count_distinct" -> DISTINCT;
                case "mode" -> MODE;
                case "string_agg" -> STRING_AGG;
                case "values" -> VALUES;
                case "first" -> FIRST;
                default -> null;
            };
        }
    }

    /**
     * One push-down part: {@code fn} over {@code of} for the rows where {@code where} holds. A COUNT counts rows ({@code of} is null):
     * a count of a value's rows says so in its condition ({@code x != null}).
     */
    public record Part(Aggregate.Fn fn, Ast of, Ast where) {
        /** Canonical: two equal parts of two aggregates are read once. */
        public String key() {
            return fn + "(" + (of == null ? "" : of.el()) + ")" + (where == null ? "" : " where " + where.el());
        }
    }

    /** The aggregate a call of a formula is; IllegalArgumentException in words when its arguments do not fit the function. */
    public static Agg of(Ast.Call c, Ast where) {
        Fn fn = Fn.named(c.name());
        if (fn == null) {
            throw new IllegalArgumentException(c.name() + " is not an aggregate");
        }
        for (String k : c.named().keySet()) {
            if (!"where".equals(k)) {
                throw new IllegalArgumentException(c.name() + "(...) takes no argument named " + k);
            }
        }
        Ast own = c.arg("where");
        Ast w = own == null ? where : Ast.and(where, own);
        List<Ast> a = c.args();
        return switch (fn) {
            case COUNT -> {
                if (a.size() > 1) {
                    throw new IllegalArgumentException("count takes one value at most: count() or count(x)");
                }
                yield new Agg(fn, a.isEmpty() ? null : a.get(0), null, 0, w);
            }
            case WAVG, SUM_ONCE, COUNT_ONCE, AVG_ONCE, FIRST -> {
                if (a.size() != 2) {
                    throw new IllegalArgumentException(c.name() + " takes two values: " + c.name() + "(x, " + (fn == Fn.WAVG ? "weight" : fn == Fn.FIRST ? "order" : "parent") + ")");
                }
                yield new Agg(fn, a.get(0), a.get(1), 0, w);
            }
            case PERCENTILE -> {
                if (a.size() != 2 || !(a.get(1) instanceof Ast.Num n) || n.value() < 0 || n.value() > 1) {
                    throw new IllegalArgumentException("percentile takes a value and a fraction from 0 to 1: percentile(PNL, 0.99)");
                }
                yield new Agg(fn, a.get(0), null, n.value(), w);
            }
            default -> {
                if (a.size() != 1) {
                    throw new IllegalArgumentException(c.name() + " takes one value: " + c.name() + "(x)");
                }
                yield new Agg(fn, a.get(0), null, 0, w);
            }
        };
    }

    /** Canonical text: the aggregate's identity. */
    public String key() {
        StringBuilder b = new StringBuilder(fn.name().toLowerCase(Locale.ROOT)).append('(');
        if (arg != null) {
            b.append(arg.el());
        }
        if (arg2 != null) {
            b.append(", ").append(arg2.el());
        }
        if (fn == Fn.PERCENTILE) {
            b.append(", ").append(p);
        }
        b.append(')');
        if (where != null) {
            b.append(" where ").append(where.el());
        }
        return b.toString();
    }

    /** The parts a mergeable aggregate is made of (empty for a holistic one). */
    public List<Part> parts() {
        Ast notNull = arg == null ? null : new Ast.Binary("!=", arg, new Ast.Null());
        List<Part> out = new ArrayList<>();
        switch (fn) {
            case SUM -> out.add(new Part(Aggregate.Fn.SUM, arg, where));
            case COUNT -> out.add(new Part(Aggregate.Fn.COUNT, null, Ast.and(where, notNull)));
            case MIN -> out.add(new Part(Aggregate.Fn.MIN, arg, where));
            case MAX -> out.add(new Part(Aggregate.Fn.MAX, arg, where));
            case AVG -> {
                out.add(new Part(Aggregate.Fn.SUM, arg, where));
                out.add(new Part(Aggregate.Fn.COUNT, null, Ast.and(where, notNull)));
            }
            case WAVG -> {
                Ast both = Ast.and(Ast.and(where, notNull), new Ast.Binary("!=", arg2, new Ast.Null()));
                out.add(new Part(Aggregate.Fn.SUM, new Ast.Binary("*", arg, arg2), both));
                out.add(new Part(Aggregate.Fn.SUM, arg2, both));
            }
            case VARIANCE, VARIANCE_POP, STDDEV, STDDEV_POP -> {
                out.add(new Part(Aggregate.Fn.SUM, arg, where));
                out.add(new Part(Aggregate.Fn.SUM, new Ast.Binary("*", arg, arg), where));
                out.add(new Part(Aggregate.Fn.COUNT, null, Ast.and(where, notNull)));
            }
            default -> { }
        }
        return out;
    }

    /**
     * The figure from the parts' values, in the order of {@link #parts()}: SUM, MIN and MAX as Double or null, COUNT as Long. The
     * same arithmetic on both paths, so the paths differ only by what the parts hold.
     */
    public Object finish(Object[] v) {
        return switch (fn) {
            case SUM, MIN, MAX -> num(v[0]);
            case COUNT -> (double) count(v[0]);
            case AVG -> count(v[1]) == 0 || num(v[0]) == null ? null : num(v[0]) / count(v[1]);
            case WAVG -> {
                Double sw = num(v[1]);
                yield sw == null || sw == 0 || num(v[0]) == null ? null : num(v[0]) / sw;
            }
            case VARIANCE, VARIANCE_POP, STDDEV, STDDEV_POP -> {
                long n = count(v[2]);
                boolean pop = fn == Fn.VARIANCE_POP || fn == Fn.STDDEV_POP;
                if (n == 0 || !pop && n < 2) {
                    yield null;
                }
                double s = num(v[0]);
                double ss = num(v[1]);
                double var = Math.max(0, (ss - s * s / n) / (pop ? n : n - 1));
                yield fn == Fn.STDDEV || fn == Fn.STDDEV_POP ? Math.sqrt(var) : var;
            }
            default -> throw new IllegalStateException(fn + " has no parts");
        };
    }

    /** Why the figure is empty, in words. */
    public String emptyNote() {
        return switch (fn) {
            case VARIANCE, STDDEV -> "fewer than two values in the cell";
            case WAVG -> "no weight in the cell";
            default -> "no values in the cell";
        };
    }

    private static Double num(Object o) {
        return o == null ? null : ((Number) o).doubleValue();
    }

    private static long count(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    /** Every aggregate call in a formula, in the order written (an aggregate inside an aggregate is refused). */
    public static List<Ast.Call> calls(Ast formula) {
        List<Ast.Call> out = new ArrayList<>();
        formula.walk(a -> {
            if (a instanceof Ast.Call c && Fn.named(c.name()) != null) {
                for (Ast x : c.args()) {
                    x.walk(y -> {
                        if (y instanceof Ast.Call inner && Fn.named(inner.name()) != null) {
                            throw new IllegalArgumentException("an aggregate inside an aggregate: " + inner.el() + " in " + c.el());
                        }
                    });
                }
                out.add(c);
            }
        });
        return out;
    }

    /** The fields an aggregate reads. */
    public void fields(java.util.Set<String> into) {
        for (Ast a : new Ast[] {arg, arg2, where}) {
            if (a != null) {
                a.walk(x -> {
                    if (x instanceof Ast.Ref r) {
                        into.add(r.name());
                    }
                });
            }
        }
    }

    @Override
    public String toString() {
        return key();
    }

}
