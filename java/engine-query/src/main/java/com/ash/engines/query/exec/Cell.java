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
import com.ash.engines.query.expr.Eval;
import com.ash.engines.query.plan.Agg;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The states of one cell: one value per push-down part (in the plan's part order; part 0 is the cell's row count) and one holistic
 * state per holistic aggregate. On the scan path the parts are computed here with the arithmetic of the kit's exact executor: a SUM
 * is exact (every double added without rounding, rounded once at the end), so it does not depend on the order rows arrive in; MIN and
 * MAX compare by {@link Double#compare}. On the push-down path the parts are the source's.
 */
public final class Cell {

    final Object[] parts;
    final Object[] holistic;

    Cell(int parts, int holistic) {
        this.parts = new Object[parts];
        this.holistic = new Object[holistic];
    }

    /** The rows that made the cell. */
    public long rows() {
        return parts[0] == null ? 0 : ((Number) parts[0]).longValue();
    }

    /** A part's value: SUM, MIN, MAX a Double or null; COUNT a Long. */
    public Object part(int i) {
        Object o = parts[i];
        return o instanceof BigDecimal b ? (Object) b.doubleValue() : o;
    }

    /** A holistic aggregate's state. */
    public Object holistic(int i) {
        return holistic[i];
    }

    /** Adds one row's value to a part (scan path). */
    void add(int i, Aggregate.Fn fn, Object v) {
        if (fn == Aggregate.Fn.COUNT) {
            parts[i] = parts[i] == null ? 1L : (Long) parts[i] + 1;
            return;
        }
        if (v == null) {
            return;
        }
        double d = Eval.number(v);
        if (Double.isNaN(d)) {
            return;
        }
        switch (fn) {
            case SUM -> parts[i] = parts[i] == null ? new BigDecimal(d) : ((BigDecimal) parts[i]).add(new BigDecimal(d));
            case MIN -> parts[i] = parts[i] == null || Double.compare(d, (Double) parts[i]) < 0 ? (Object) d : parts[i];
            case MAX -> parts[i] = parts[i] == null || Double.compare(d, (Double) parts[i]) > 0 ? (Object) d : parts[i];
            default -> throw new IllegalStateException();
        }
    }

    /** Sets a part from a grouped scan's batch (push-down path). */
    void set(int i, Object v) {
        parts[i] = v;
    }

    /** Adds one row to a holistic aggregate's state (scan path). */
    @SuppressWarnings("unchecked")
    void addHolistic(int i, Agg a, Object x, Object y) {
        switch (a.fn()) {
            case SUM_ONCE, COUNT_ONCE, AVG_ONCE -> {
                if (x == null || y == null || Double.isNaN(Eval.number(x))) {
                    return;
                }
                TreeMap<String, TreeSet<Double>> m = holistic[i] == null ? new TreeMap<>() : (TreeMap<String, TreeSet<Double>>) holistic[i];
                m.computeIfAbsent(Eval.text(y), k -> new TreeSet<>()).add(Eval.number(x));
                holistic[i] = m;
            }
            case FIRST -> {
                if (x == null) {
                    return;
                }
                String order = y == null ? "" : Eval.text(y);
                Object[] cur = (Object[]) holistic[i];
                if (cur == null || order.compareTo((String) cur[0]) < 0) {
                    holistic[i] = new Object[] {order, x};
                }
            }
            default -> {
                if (x == null) {
                    return;
                }
                List<Object> l = holistic[i] == null ? new ArrayList<>() : (List<Object>) holistic[i];
                if (a.fn() == Agg.Fn.MEDIAN || a.fn() == Agg.Fn.PERCENTILE) {
                    double d = Eval.number(x);
                    if (Double.isNaN(d)) {
                        return;
                    }
                    l.add(d);
                } else {
                    l.add(x);
                }
                holistic[i] = l;
            }
        }
    }

    /** A holistic aggregate's figure: a Double, a String, or null. Order-free: every state is sorted before it is read. */
    @SuppressWarnings("unchecked")
    public static Object finishHolistic(Agg a, Object state) {
        if (state == null) {
            return a.fn() == Agg.Fn.DISTINCT || a.fn() == Agg.Fn.COUNT_ONCE ? (Object) 0.0 : null;
        }
        switch (a.fn()) {
            case SUM_ONCE, COUNT_ONCE, AVG_ONCE -> {
                Map<String, TreeSet<Double>> m = (Map<String, TreeSet<Double>>) state;
                if (a.fn() == Agg.Fn.COUNT_ONCE) {
                    return (double) m.size();
                }
                BigDecimal s = BigDecimal.ZERO;
                int n = 0;
                for (TreeSet<Double> vs : m.values()) {
                    for (double v : vs) {                            // each distinct (parent, value) once
                        s = s.add(new BigDecimal(v));
                        n++;
                    }
                }
                return a.fn() == Agg.Fn.SUM_ONCE ? s.doubleValue() : s.doubleValue() / n;
            }
            case FIRST -> {
                return Eval.plain(((Object[]) state)[1]);
            }
            case MEDIAN, PERCENTILE -> {
                List<Double> l = new ArrayList<>((List<Double>) (List<?>) state);
                Collections.sort(l);
                double q = a.fn() == Agg.Fn.MEDIAN ? 0.5 : a.p();
                double pos = q * (l.size() - 1);
                int lo = (int) Math.floor(pos);
                int hi = (int) Math.ceil(pos);
                return lo == hi ? l.get(lo) : l.get(lo) + (pos - lo) * (l.get(hi) - l.get(lo));
            }
            case DISTINCT -> {
                TreeSet<String> s = new TreeSet<>();
                ((List<Object>) state).forEach(o -> s.add(Eval.text(Eval.plain(o))));
                return (double) s.size();
            }
            case VALUES -> {
                TreeSet<String> s = new TreeSet<>();
                ((List<Object>) state).forEach(o -> s.add(Eval.text(Eval.plain(o))));
                StringBuilder b = new StringBuilder("[");
                for (String x : s) {
                    b.append(b.length() == 1 ? "" : ",").append('"').append(x.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
                }
                return b.append(']').toString();
            }
            case MODE -> {
                TreeMap<String, Integer> counts = new TreeMap<>();
                Map<String, Object> first = new TreeMap<>();
                for (Object o : (List<Object>) state) {
                    String k = Eval.text(Eval.plain(o));
                    counts.merge(k, 1, Integer::sum);
                    first.putIfAbsent(k, Eval.plain(o));
                }
                String best = null;
                int n = 1;
                for (Map.Entry<String, Integer> e : counts.entrySet()) {   // ties to the smallest: the first met in sorted order
                    if (e.getValue() > n) {
                        n = e.getValue();
                        best = e.getKey();
                    }
                }
                return best == null ? null : first.get(best);
            }
            case STRING_AGG -> {
                TreeSet<String> s = new TreeSet<>();
                ((List<Object>) state).forEach(o -> s.add(Eval.text(Eval.plain(o))));
                return String.join(", ", s);
            }
            default -> throw new IllegalStateException(a.fn() + " is not holistic");
        }
    }
}
