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
package com.ash.engines.capacity;

import com.ash.engines.capacity.Expr.Bin;
import com.ash.engines.capacity.Expr.Call;
import com.ash.engines.capacity.Expr.Neg;
import com.ash.engines.capacity.Expr.Node;
import com.ash.engines.capacity.Expr.Num;
import com.ash.engines.capacity.Expr.Ref;
import com.ash.engines.capacity.Expr.Str;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The planner's one evaluator: the cells of a plan (inputs and coefficients as values, every figure as an expression tree), each worked
 * out once, on demand, by name. A value is a Double, a String or a Boolean. Arithmetic is IEEE double, the power is {@link StrictMath#pow},
 * so two machines give the same bits; text is written as the planner's workbook writes it (a whole number without a point, a fraction as the
 * shortest digits, an exponent with two digits).
 */
final class Evaluator {

    /** A name that cannot be evaluated, or an expression whose types do not fit. */
    static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Failure(String words) {
            super(words);
        }
    }

    private final Map<String, Object> cells;
    private final Map<String, Object> memo = new HashMap<>();
    private final Set<String> working = new HashSet<>();

    /** {@code cells}: name to a constant (Double or String) or an expression ({@link Node}). */
    Evaluator(Map<String, Object> cells) {
        this.cells = cells;
    }

    /** The value of a named cell. */
    Object value(String name) {
        Object known = memo.get(name);
        if (known != null) {
            return known;
        }
        if (!cells.containsKey(name)) {
            throw new Failure("no name " + name);
        }
        if (!working.add(name)) {
            throw new Failure(name + " depends on itself");
        }
        try {
            Object c = cells.get(name);
            Object v = c instanceof Node n ? eval(n) : c;
            memo.put(name, v);
            return v;
        } finally {
            working.remove(name);
        }
    }

    /** The value of an expression over the cells. */
    Object eval(Node n) {
        return switch (n) {
            case Num x -> x.value();
            case Str s -> s.value();
            case Ref r -> value(r.name());
            case Neg x -> -num(eval(x.operand()));
            case Bin b -> binary(b);
            case Call c -> call(c);
        };
    }

    private Object binary(Bin b) {
        Object l = eval(b.left());
        Object r = eval(b.right());
        switch (b.op()) {
            case "+":
                return num(l) + num(r);
            case "-":
                return num(l) - num(r);
            case "*":
                return num(l) * num(r);
            case "/":
                return num(l) / num(r);
            case "^":
                return StrictMath.pow(num(l), num(r));
            case "&":
                return text(l) + text(r);
            default:
                return compare(b.op(), l, r);
        }
    }

    private static Object compare(String op, Object a, Object b) {
        if (a instanceof String x && b instanceof String y) {
            int c = x.toLowerCase(java.util.Locale.ROOT).compareTo(y.toLowerCase(java.util.Locale.ROOT));
            return order(op, c);
        }
        if (a instanceof String || b instanceof String) {
            if (op.equals("=") || op.equals("<>")) {
                return op.equals("<>");
            }
            throw new Failure("text compared with a number by " + op);
        }
        double x = num(a);
        double y = num(b);
        return switch (op) {
            case "=" -> x == y;
            case "<>" -> x != y;
            case "<" -> x < y;
            case ">" -> x > y;
            case "<=" -> x <= y;
            default -> x >= y;
        };
    }

    private static boolean order(String op, int c) {
        return switch (op) {
            case "=" -> c == 0;
            case "<>" -> c != 0;
            case "<" -> c < 0;
            case ">" -> c > 0;
            case "<=" -> c <= 0;
            default -> c >= 0;
        };
    }

    private Object call(Call c) {
        if (c.fn().equals("IF")) {
            return truthy(eval(c.args().get(0))) ? eval(c.args().get(1)) : eval(c.args().get(2));
        }
        List<Object> a = c.args().stream().map(this::eval).toList();
        switch (c.fn()) {
            case "MAX": {
                double m = Double.NEGATIVE_INFINITY;
                for (Object o : a) {
                    double d = num(o);
                    m = Double.isNaN(d) || Double.isNaN(m) ? Double.NaN : Math.max(m, d);
                }
                return m;
            }
            case "MIN": {
                double m = Double.POSITIVE_INFINITY;
                for (Object o : a) {
                    double d = num(o);
                    m = Double.isNaN(d) || Double.isNaN(m) ? Double.NaN : Math.min(m, d);
                }
                return m;
            }
            case "CEILING":
                return Math.ceil(num(a.get(0)) / num(a.get(1)) - 1e-12) * num(a.get(1));
            case "AND":
                return a.stream().allMatch(Evaluator::truthy);
            case "OR":
                return a.stream().anyMatch(Evaluator::truthy);
            case "CHAR":
                return String.valueOf((char) (int) num(a.get(0)));
            case "INT":
                return Math.floor(num(a.get(0)));
            case "ROUND":
                return round(num(a.get(0)), (int) num(a.get(1)));
            default:
                throw new Failure("function " + c.fn() + " is not supported");
        }
    }

    static double num(Object v) {
        if (v instanceof String s) {
            throw new Failure("text \"" + s + "\" used as a number");
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        return (Double) v;
    }

    private static boolean truthy(Object v) {
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        if (v instanceof Boolean b) {
            return b;
        }
        double d = (Double) v;
        return d != 0 && !Double.isNaN(d);
    }

    /** Python's round(x, n): half to even on the binary value, as the planner's figures were computed. */
    static double round(double x, int n) {
        double p = StrictMath.pow(10, n);
        double y = x * p;
        double r = Math.floor(y + 0.5);
        if (Math.abs(y % 1) == 0.5) {
            r = 2 * Math.floor(y / 2 + 0.5);
        }
        return r / p;
    }

    // ---------------------------------------------------------------------------------------------------- text

    /** A value as text, as a join writes it: TRUE, FALSE, a whole number plain, a fraction as the shortest digits (1e-05, not 1e-5). */
    static String text(Object v) {
        if (v instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        if (v instanceof Double d) {
            return number(d);
        }
        return String.valueOf(v);
    }

    /** JavaScript's {@code String(x)} of a number, with Python's two-digit exponent for a fraction. */
    static String number(double x) {
        if (Double.isNaN(x)) {
            return "NaN";
        }
        if (Double.isInfinite(x)) {
            return x > 0 ? "Infinity" : "-Infinity";
        }
        if (x == 0) {
            return "0";
        }
        boolean whole = x == Math.rint(x);
        BigDecimal b = new BigDecimal(Double.toString(Math.abs(x))).stripTrailingZeros();
        String digits = b.unscaledValue().toString();
        int k = digits.length();
        int n = k - b.scale();                              // the decimal point stands after n digits
        StringBuilder out = new StringBuilder(x < 0 ? "-" : "");
        if (k <= n && n <= 21) {
            out.append(digits).append("0".repeat(n - k));
        } else if (0 < n && n <= 21) {
            out.append(digits, 0, n).append('.').append(digits.substring(n));
        } else if (-6 < n && n <= 0) {
            out.append("0.").append("0".repeat(-n)).append(digits);
        } else {
            int e = n - 1;
            out.append(digits.charAt(0));
            if (k > 1) {
                out.append('.').append(digits.substring(1));
            }
            String exp = String.valueOf(Math.abs(e));
            out.append('e').append(e < 0 ? '-' : '+').append(whole || exp.length() > 1 ? exp : "0" + exp);
        }
        return out.toString();
    }
}
