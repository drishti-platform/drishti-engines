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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * A parsed Rachana-EL expression: a measure's formula or a row expression. {@link #el()} prints it back as Rachana-EL text in one
 * canonical form (the text a grouped scan carries and an explanation step shows), so two spellings of one expression print the same.
 */
public sealed interface Ast {

    /** The expression as Rachana-EL text, canonical. */
    String el();

    /** Every node, this one first. */
    default void walk(Consumer<Ast> sink) {
        sink.accept(this);
    }

    /** This node with every child mapped (bottom-up callers map children first). */
    default Ast map(UnaryOperator<Ast> f) {
        return f.apply(this);
    }

    /** A number. {@code whole}: written without a fraction or an exponent. */
    record Num(double value, boolean whole) implements Ast {
        @Override public String el() {
            if (whole && Math.abs(value) < 1e15) {
                return Long.toString((long) value);
            }
            return Double.toString(value);
        }
    }

    /** A text literal. */
    record Str(String value) implements Ast {
        @Override public String el() {
            return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
        }
    }

    /** {@code true} or {@code false}. */
    record Bool(boolean value) implements Ast {
        @Override public String el() {
            return Boolean.toString(value);
        }
    }

    /** {@code null}. */
    record Null() implements Ast {
        @Override public String el() {
            return "null";
        }
    }

    /** A field of the row (bare {@code PNL} or {@code $.PNL}; a dotted model name {@code trades.desk} is one field). */
    record Ref(String name) implements Ast {
        @Override public String el() {
            if (name.startsWith("step.") || name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                return name;                                         // a field, or an earlier explanation step (step.a0)
            }
            return "$['" + name.replace("'", "\\'") + "']";
        }
    }

    /** {@code [a, b]}. */
    record ListOf(List<Ast> items) implements Ast {
        public ListOf {
            items = List.copyOf(items);
        }
        @Override public String el() {
            List<String> s = new ArrayList<>();
            items.forEach(i -> s.add(i.el()));
            return "[" + String.join(", ", s) + "]";
        }
        @Override public void walk(Consumer<Ast> sink) {
            sink.accept(this);
            items.forEach(i -> i.walk(sink));
        }
        @Override public Ast map(UnaryOperator<Ast> f) {
            return f.apply(new ListOf(items.stream().map(i -> i.map(f)).toList()));
        }
    }

    /** {@code -x} or {@code !x} / {@code not x}. */
    record Unary(String op, Ast operand) implements Ast {
        @Override public String el() {
            return "-".equals(op) ? "-(" + operand.el() + ")" : "!(" + operand.el() + ")";
        }
        @Override public void walk(Consumer<Ast> sink) {
            sink.accept(this);
            operand.walk(sink);
        }
        @Override public Ast map(UnaryOperator<Ast> f) {
            return f.apply(new Unary(op, operand.map(f)));
        }
    }

    /** A binary operator: {@code + - * / % == != < <= > >= && ||}. */
    record Binary(String op, Ast left, Ast right) implements Ast {
        @Override public String el() {
            return "(" + left.el() + " " + op + " " + right.el() + ")";
        }
        @Override public void walk(Consumer<Ast> sink) {
            sink.accept(this);
            left.walk(sink);
            right.walk(sink);
        }
        @Override public Ast map(UnaryOperator<Ast> f) {
            return f.apply(new Binary(op, left.map(f), right.map(f)));
        }
    }

    /** {@code x in list}, {@code x not in list}. */
    record In(Ast value, Ast list, boolean negated) implements Ast {
        @Override public String el() {
            return "(" + value.el() + (negated ? " not in " : " in ") + list.el() + ")";
        }
        @Override public void walk(Consumer<Ast> sink) {
            sink.accept(this);
            value.walk(sink);
            list.walk(sink);
        }
        @Override public Ast map(UnaryOperator<Ast> f) {
            return f.apply(new In(value.map(f), list.map(f), negated));
        }
    }

    /** {@code c ? a : b}. */
    record Ternary(Ast condition, Ast then, Ast otherwise) implements Ast {
        @Override public String el() {
            return "(" + condition.el() + " ? " + then.el() + " : " + otherwise.el() + ")";
        }
        @Override public void walk(Consumer<Ast> sink) {
            sink.accept(this);
            condition.walk(sink);
            then.walk(sink);
            otherwise.walk(sink);
        }
        @Override public Ast map(UnaryOperator<Ast> f) {
            return f.apply(new Ternary(condition.map(f), then.map(f), otherwise.map(f)));
        }
    }

    /** A call: positional arguments, then named ones ({@code where = ...}), in the order written. */
    record Call(String name, List<Ast> args, Map<String, Ast> named) implements Ast {
        public Call {
            args = List.copyOf(args);
            named = Collections.unmodifiableMap(new LinkedHashMap<>(named == null ? Map.of() : named));
        }
        public Call(String name, List<Ast> args) {
            this(name, args, Map.of());
        }
        /** The named argument, or null. */
        public Ast arg(String key) {
            return named.get(key);
        }
        @Override public String el() {
            List<String> s = new ArrayList<>();
            args.forEach(a -> s.add(a.el()));
            named.forEach((k, v) -> s.add(k + " = " + v.el()));
            return name + "(" + String.join(", ", s) + ")";
        }
        @Override public void walk(Consumer<Ast> sink) {
            sink.accept(this);
            args.forEach(a -> a.walk(sink));
            named.values().forEach(a -> a.walk(sink));
        }
        @Override public Ast map(UnaryOperator<Ast> f) {
            Map<String, Ast> n = new LinkedHashMap<>();
            named.forEach((k, v) -> n.put(k, v.map(f)));
            return f.apply(new Call(name, args.stream().map(a -> a.map(f)).toList(), n));
        }
    }

    /** {@code a && b}, built for conditions (null parts are left out; none: null). */
    static Ast and(Ast a, Ast b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return new Binary("&&", a, b);
    }
}
