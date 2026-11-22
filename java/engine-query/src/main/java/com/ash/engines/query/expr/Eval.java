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
package com.ash.engines.query.expr;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Evaluates an {@link Ast} with Rachana-EL's value rules (rachana-core {@code el.Expr}), so a row expression gives here exactly what
 * the host's executor and the kit's evaluator give for the same text: arithmetic on null is null ({@code +} joins a text with
 * nothing), division by zero is null, comparisons of a number with nothing are false, two texts that are not numbers compare as
 * text (ISO dates order), {@code null} equals only {@code null}.
 *
 * <p>Values are {@code Double}, {@code String}, {@code Boolean} or null. Fields are read through {@code fields}; a call that is not a
 * scalar function (an aggregate in a measure's formula) through {@code calls}, which may return a value or throw.
 */
public final class Eval {

    /** The scalar functions, by name: what a formula may call besides aggregates. */
    public static final Set<String> SCALARS = Set.of("abs", "round", "floor", "ceiling", "ceil", "power", "sqrt", "ln", "log", "log10", "exp",
            "sign", "coalesce", "greatest", "least", "ratio", "nonblank", "lower", "upper", "len", "if", "year", "month", "substring", "contains", "starts_with");

    private final Function<String, Object> fields;
    private final Function<Ast.Call, Object> calls;

    public Eval(Function<String, Object> fields, Function<Ast.Call, Object> calls) {
        this.fields = fields;
        this.calls = calls;
    }

    /** The value of {@code a}. */
    public Object eval(Ast a) {
        return switch (a) {
            case Ast.Num n -> n.value();
            case Ast.Str s -> s.value();
            case Ast.Bool b -> b.value();
            case Ast.Null x -> null;
            case Ast.Ref r -> plain(fields.apply(r.name()));
            case Ast.ListOf l -> {
                List<Object> out = new ArrayList<>();
                l.items().forEach(i -> out.add(eval(i)));
                yield out;
            }
            case Ast.Unary u -> {
                Object v = eval(u.operand());
                if ("-".equals(u.op())) {
                    yield v == null ? null : -number(v);
                }
                yield !truthy(v);
            }
            case Ast.Binary b -> binary(b);
            case Ast.In in -> {
                Object v = eval(in.value());
                Object l = eval(in.list());
                boolean found = false;
                if (l instanceof List<?> list) {
                    for (Object x : list) {
                        if (equal(v, x)) {
                            found = true;
                            break;
                        }
                    }
                }
                yield in.negated() != found;
            }
            case Ast.Ternary t -> truthy(eval(t.condition())) ? eval(t.then()) : eval(t.otherwise());
            case Ast.Call c -> SCALARS.contains(c.name()) ? scalar(c) : plain(calls.apply(c));
        };
    }

    private Object binary(Ast.Binary b) {
        if ("&&".equals(b.op())) {
            return truthy(eval(b.left())) && truthy(eval(b.right()));
        }
        if ("||".equals(b.op())) {
            return truthy(eval(b.left())) || truthy(eval(b.right()));
        }
        Object x = eval(b.left());
        Object y = eval(b.right());
        switch (b.op()) {
            case "==" -> {
                return equal(x, y);
            }
            case "!=" -> {
                return !equal(x, y);
            }
            case "+" -> {
                if (x == null && (y == null || y instanceof Double) || y == null && x instanceof Double) {
                    return null;
                }
                if (!(x instanceof Double && y instanceof Double)) {
                    return text(x) + text(y);
                }
                return (Double) x + (Double) y;
            }
            case "-", "*", "/", "%" -> {
                if (x == null || y == null) {
                    return null;
                }
            }
            default -> { }
        }
        double l = number(x);
        double r = number(y);
        if ((Double.isNaN(l) || Double.isNaN(r)) && x instanceof String sa && y instanceof String sb && Set.of("<", "<=", ">", ">=").contains(b.op())) {
            int o = sa.compareTo(sb);
            return switch (b.op()) {
                case "<" -> o < 0;
                case "<=" -> o <= 0;
                case ">" -> o > 0;
                default -> o >= 0;
            };
        }
        return switch (b.op()) {
            case "-" -> l - r;
            case "*" -> l * r;
            case "/" -> r == 0 ? null : l / r;
            case "%" -> r == 0 ? null : l % r;
            case "<" -> l < r;
            case "<=" -> l <= r;
            case ">" -> l > r;
            case ">=" -> l >= r;
            default -> throw new IllegalArgumentException("an operator that is not known: " + b.op());
        };
    }

    private Object scalar(Ast.Call c) {
        List<Object> v = new ArrayList<>();
        for (Ast a : c.args()) {
            v.add(eval(a));
        }
        Object x = v.isEmpty() ? null : v.get(0);
        switch (c.name()) {
            case "coalesce" -> {
                for (Object o : v) {
                    if (o != null) {
                        return o;
                    }
                }
                return null;
            }
            case "greatest", "least" -> {
                Double best = null;
                for (Object o : v) {
                    if (o == null) {
                        continue;
                    }
                    double d = number(o);
                    if (Double.isNaN(d)) {
                        continue;
                    }
                    if (best == null || ("greatest".equals(c.name()) ? d > best : d < best)) {
                        best = d;
                    }
                }
                return best;
            }
            case "nonblank" -> {
                return x != null && !text(x).isBlank();
            }
            case "if" -> {
                return truthy(x) ? (v.size() > 1 ? v.get(1) : null) : (v.size() > 2 ? v.get(2) : null);
            }
            case "contains", "starts_with" -> {
                if (x == null || v.size() < 2 || v.get(1) == null) {
                    return false;
                }
                return "contains".equals(c.name()) ? text(x).contains(text(v.get(1))) : text(x).startsWith(text(v.get(1)));
            }
            case "lower" -> {
                return x == null ? null : text(x).toLowerCase(java.util.Locale.ROOT);
            }
            case "upper" -> {
                return x == null ? null : text(x).toUpperCase(java.util.Locale.ROOT);
            }
            case "len" -> {
                return x == null ? null : (double) text(x).length();
            }
            case "substring" -> {
                if (x == null) {
                    return null;
                }
                String s = text(x);
                int from = (int) Math.max(0, Math.min(s.length(), number(v.get(1))));
                int to = v.size() > 2 ? (int) Math.max(from, Math.min(s.length(), number(v.get(2)))) : s.length();
                return s.substring(from, to);
            }
            case "year", "month" -> {
                if (x == null || text(x).length() < 7) {
                    return null;
                }
                String s = text(x);
                try {
                    return "year".equals(c.name()) ? Double.parseDouble(s.substring(0, 4)) : Double.parseDouble(s.substring(5, 7));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            default -> { }
        }
        for (Object o : v) {
            if (o == null) {
                return null;                                         // null in, null out
            }
        }
        double d = number(x);
        return switch (c.name()) {
            case "abs" -> Math.abs(d);
            case "round" -> round(d, v.size() > 1 ? (int) number(v.get(1)) : 0);
            case "floor" -> Math.floor(d);
            case "ceiling", "ceil" -> Math.ceil(d);
            case "power" -> finite(Math.pow(d, number(v.get(1))));
            case "sqrt" -> d < 0 ? null : Math.sqrt(d);
            case "ln", "log" -> d <= 0 ? null : Math.log(d);
            case "log10" -> d <= 0 ? null : Math.log10(d);
            case "exp" -> finite(Math.exp(d));
            case "sign" -> Math.signum(d);
            case "ratio" -> number(v.get(1)) == 0 ? null : d / number(v.get(1));
            default -> throw new IllegalArgumentException("a function that is not known: " + c.name());
        };
    }

    private static Double finite(double d) {
        return Double.isFinite(d) ? d : null;
    }

    /** Half away from zero at {@code places} decimals, on the decimal the double prints as. */
    public static double round(double d, int places) {
        if (!Double.isFinite(d)) {
            return d;
        }
        return BigDecimal.valueOf(d).setScale(places, RoundingMode.HALF_UP).doubleValue();
    }

    /** Integers and floats as Double; texts, booleans and null as they are. */
    public static Object plain(Object v) {
        if (v instanceof Double || v == null || v instanceof String || v instanceof Boolean || v instanceof List<?>) {
            return v;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return v.toString();
    }

    /** True for true, and for any value that is not false, null, NaN or an empty text. */
    public static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Double d) {
            return !d.isNaN() && d != 0;
        }
        return !(v instanceof String s) || !s.isEmpty();
    }

    /** A number: a Double, a text that reads as one, else NaN. */
    public static double number(Object v) {
        if (v instanceof Double d) {
            return d;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.strip());
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    /** {@code null} equals only {@code null}; numbers by value; anything else by its text. */
    public static boolean equal(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Double x && b instanceof Double y) {
            return x.doubleValue() == y.doubleValue();
        }
        return text(a).equals(text(b));
    }

    /** A value's text: a whole number without a fraction. */
    public static String text(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Double d) {
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                return Long.toString(d.longValue());
            }
            return d.toString();
        }
        return v.toString();
    }
}
