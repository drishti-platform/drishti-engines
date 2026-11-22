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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The planner's expressions: a small Excel subset over names, read once into a tree. Operators {@code + - * / ^ &} (text join),
 * comparisons ({@code = <> < > <= >=}), and the functions IF, MAX, MIN, CEILING, INT, ROUND, AND, OR and CHAR. The tree is evaluated by
 * {@link Evaluator} (the one evaluator of the planner), written as a Rachana-EL formula by {@link #toEl} (the explanation's steps) and as
 * a spreadsheet formula by {@link #toExcel} (the workbook): three renderings of one tree, so they cannot disagree about what a name means.
 */
final class Expr {

    /** A node of an expression. */
    sealed interface Node permits Num, Str, Ref, Neg, Bin, Call {
    }

    /** A number; {@code text} is how the file wrote it. */
    record Num(double value, String text) implements Node {
    }

    /** A text literal. */
    record Str(String value) implements Node {
    }

    /** A named cell: an input, a coefficient or another figure. */
    record Ref(String name) implements Node {
    }

    /** Unary minus. */
    record Neg(Node operand) implements Node {
    }

    /** A binary operator: {@code + - * / ^ & = <> < > <= >=}. */
    record Bin(String op, Node left, Node right) implements Node {
    }

    /** A function call; {@code fn} in upper case. */
    record Call(String fn, List<Node> args) implements Node {
        Call {
            args = List.copyOf(args);
        }
    }

    private static final Pattern TOKEN = Pattern.compile(
            "\\s*(?:(\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)|(\"(?:[^\"]|\"\")*\")|([A-Za-z_][A-Za-z0-9_.]*)|(<=|>=|<>|[-+*/^&=<>(),]))");
    private static final Set<String> FUNCTIONS = Set.of("IF", "MAX", "MIN", "CEILING", "INT", "ROUND", "AND", "OR", "CHAR");

    private Expr() {
    }

    // ---------------------------------------------------------------------------------------------------- reading

    private record Token(char kind, String text) {
    }

    /** The expression as a tree; IllegalArgumentException, with the formula, for what cannot be read. */
    static Node parse(String formula) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < formula.length()) {
            Matcher m = TOKEN.matcher(formula);
            m.region(i, formula.length());
            if (!m.lookingAt() || m.end() == i) {
                if (formula.substring(i).isBlank()) {
                    break;
                }
                throw new IllegalArgumentException("cannot read " + formula.substring(i) + " in " + formula);
            }
            i = m.end();
            if (m.group(1) != null) {
                tokens.add(new Token('n', m.group(1)));
            } else if (m.group(2) != null) {
                String s = m.group(2);
                tokens.add(new Token('s', s.substring(1, s.length() - 1).replace("\"\"", "\"")));
            } else if (m.group(3) != null) {
                tokens.add(new Token('w', m.group(3)));
            } else {
                tokens.add(new Token('o', m.group(4)));
            }
        }
        Parser p = new Parser(tokens, formula);
        Node n = p.compare();
        if (p.pos != tokens.size()) {
            throw new IllegalArgumentException("unexpected " + tokens.get(p.pos).text() + " in " + formula);
        }
        return n;
    }

    private static final class Parser {
        private final List<Token> tokens;
        private final String formula;
        private int pos;

        Parser(List<Token> tokens, String formula) {
            this.tokens = tokens;
            this.formula = formula;
        }

        private boolean isOp(String op) {
            return pos < tokens.size() && tokens.get(pos).kind() == 'o' && tokens.get(pos).text().equals(op);
        }

        private void take(String op) {
            if (!isOp(op)) {
                throw new IllegalArgumentException("expected " + op + " in " + formula);
            }
            pos++;
        }

        Node compare() {
            Node a = concat();
            while (pos < tokens.size() && tokens.get(pos).kind() == 'o' && Set.of("=", "<>", "<", ">", "<=", ">=").contains(tokens.get(pos).text())) {
                String op = tokens.get(pos++).text();
                a = new Bin(op, a, concat());
            }
            return a;
        }

        private Node concat() {
            Node a = add();
            while (isOp("&")) {
                pos++;
                a = new Bin("&", a, add());
            }
            return a;
        }

        private Node add() {
            Node a = mul();
            while (isOp("+") || isOp("-")) {
                String op = tokens.get(pos++).text();
                a = new Bin(op, a, mul());
            }
            return a;
        }

        private Node mul() {
            Node a = pow();
            while (isOp("*") || isOp("/")) {
                String op = tokens.get(pos++).text();
                a = new Bin(op, a, pow());
            }
            return a;
        }

        private Node pow() {
            Node a = unary();
            while (isOp("^")) {
                pos++;
                a = new Bin("^", a, unary());
            }
            return a;
        }

        private Node unary() {
            if (isOp("-")) {
                pos++;
                return new Neg(unary());
            }
            return atom();
        }

        private Node atom() {
            if (pos >= tokens.size()) {
                throw new IllegalArgumentException("the expression ends early: " + formula);
            }
            Token t = tokens.get(pos++);
            switch (t.kind()) {
                case 'n':
                    return new Num(Double.parseDouble(t.text()), t.text());
                case 's':
                    return new Str(t.text());
                case 'w':
                    if (isOp("(")) {
                        return call(t.text().toUpperCase(Locale.ROOT));
                    }
                    return new Ref(t.text());
                default:
                    if (t.text().equals("(")) {
                        Node x = compare();
                        take(")");
                        return x;
                    }
                    throw new IllegalArgumentException("unexpected " + t.text() + " in " + formula);
            }
        }

        private Node call(String fn) {
            if (!FUNCTIONS.contains(fn)) {
                throw new IllegalArgumentException("function " + fn + " is not supported: " + formula);
            }
            take("(");
            List<Node> args = new ArrayList<>();
            if (!isOp(")")) {
                args.add(compare());
                while (isOp(",")) {
                    pos++;
                    args.add(compare());
                }
            }
            take(")");
            int n = args.size();
            boolean fine = switch (fn) {
                case "IF" -> n == 3;
                case "CEILING", "ROUND" -> n == 2;
                case "INT", "CHAR" -> n == 1;
                default -> n >= 1;
            };
            if (!fine) {
                throw new IllegalArgumentException(fn + " is given " + n + " arguments in " + formula);
            }
            return new Call(fn, args);
        }
    }

    // ---------------------------------------------------------------------------------------------------- walking

    /** The names the expression reads, in order of first use. */
    static Set<String> refs(Node node) {
        Set<String> out = new LinkedHashSet<>();
        collect(node, out);
        return out;
    }

    private static void collect(Node n, Set<String> out) {
        switch (n) {
            case Ref r -> out.add(r.name());
            case Neg x -> collect(x.operand(), out);
            case Bin b -> {
                collect(b.left(), out);
                collect(b.right(), out);
            }
            case Call c -> c.args().forEach(a -> collect(a, out));
            default -> {
            }
        }
    }

    /** True when the tree holds a text join or a function the family's spreadsheet formulas do not have (CHAR, INT, ROUND). */
    static boolean needsText(Node n) {
        return switch (n) {
            case Bin b -> b.op().equals("&") || needsText(b.left()) || needsText(b.right());
            case Neg x -> needsText(x.operand());
            case Call c -> Set.of("CHAR", "INT", "ROUND").contains(c.fn()) || c.args().stream().anyMatch(Expr::needsText);
            default -> false;
        };
    }

    /** True when Rachana-EL has no form for the tree (a text join, CHAR). */
    static boolean opaqueInEl(Node n) {
        return switch (n) {
            case Bin b -> b.op().equals("&") || opaqueInEl(b.left()) || opaqueInEl(b.right());
            case Neg x -> opaqueInEl(x.operand());
            case Call c -> c.fn().equals("CHAR") || c.args().stream().anyMatch(Expr::opaqueInEl);
            default -> false;
        };
    }

    /** The tree as a Rachana-EL formula; {@code name} writes a name (a given value or an earlier step). Fully parenthesised. */
    static String toEl(Node n, java.util.function.Function<String, String> name) {
        return switch (n) {
            case Num x -> x.text();
            case Str s -> '"' + s.value().replace("\\", "\\\\").replace("\"", "\\\"") + '"';
            case Ref r -> name.apply(r.name());
            case Neg x -> "(-" + toEl(x.operand(), name) + ")";
            case Bin b -> switch (b.op()) {
                case "^" -> "power(" + toEl(b.left(), name) + ", " + toEl(b.right(), name) + ")";
                case "=" -> "(" + toEl(b.left(), name) + " == " + toEl(b.right(), name) + ")";
                case "<>" -> "(" + toEl(b.left(), name) + " != " + toEl(b.right(), name) + ")";
                case "&" -> throw new IllegalArgumentException("a text join has no Rachana-EL form");
                default -> "(" + toEl(b.left(), name) + " " + b.op() + " " + toEl(b.right(), name) + ")";
            };
            case Call c -> {
                List<String> a = c.args().stream().map(x -> toEl(x, name)).toList();
                yield switch (c.fn()) {
                    case "IF" -> "if(" + a.get(0) + ", " + a.get(1) + ", " + a.get(2) + ")";
                    case "MAX" -> "max(" + String.join(", ", a) + ")";
                    case "MIN" -> "min(" + String.join(", ", a) + ")";
                    // the evaluator's own tolerance: ceil(x / s - 1e-12) * s, so a figure that lands on a step is the same step here
                    case "CEILING" -> "(ceiling((" + a.get(0) + " / " + a.get(1) + ") - 0.000000000001, 1) * " + a.get(1) + ")";
                    case "INT" -> "floor(" + a.get(0) + ")";
                    case "ROUND" -> "round(" + a.get(0) + ", " + a.get(1) + ")";
                    case "AND" -> "(" + String.join(" && ", a) + ")";
                    case "OR" -> "(" + String.join(" || ", a) + ")";
                    default -> throw new IllegalArgumentException("function " + c.fn() + " has no Rachana-EL form");
                };
            }
        };
    }

    /**
     * The tree as a spreadsheet formula (no leading {@code =}) over defined names. CEILING is written with ROUNDUP and the evaluator's own
     * tolerance (ceil(x / s - 1e-12) * s), so the sheet and the engine agree on a figure that lands on a step. The tree must not need text
     * ({@link #needsText}); the workbook holds such a figure as a value.
     */
    static String toExcel(Node n) {
        return switch (n) {
            case Num x -> x.text();
            case Str s -> '"' + s.value().replace("\"", "\"\"") + '"';
            case Ref r -> r.name();
            case Neg x -> "(-" + toExcel(x.operand()) + ")";
            case Bin b -> "(" + toExcel(b.left()) + b.op() + toExcel(b.right()) + ")";
            case Call c -> {
                List<String> a = c.args().stream().map(Expr::toExcel).toList();
                yield switch (c.fn()) {
                    case "CEILING" -> "(ROUNDUP(" + a.get(0) + "/" + a.get(1) + "-0.000000000001,0)*" + a.get(1) + ")";
                    default -> c.fn() + "(" + String.join(",", a) + ")";
                };
            }
        };
    }
}
