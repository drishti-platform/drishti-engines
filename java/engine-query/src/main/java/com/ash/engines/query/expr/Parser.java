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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the part of Rachana-EL a query uses, with Rachana's grammar and precedence (rachana-core {@code el.Parser}):
 *
 * <pre>
 * expr     = or [ "?" expr ":" expr ] ;
 * or       = and { ("||" | "or") and } ;
 * and      = not { ("&amp;&amp;" | "and") not } ;
 * not      = "not" not | equality ;
 * equality = compare { ("==" | "!=") compare } ;
 * compare  = sum { ("&lt;" | "&lt;=" | "&gt;" | "&gt;=") sum | ["not"] "in" sum } ;
 * sum      = product { ("+" | "-") product } ;
 * product  = unary { ("*" | "/" | "%") unary } ;
 * unary    = ("!" | "-" | "+") unary | postfix ;
 * postfix  = primary { "." ident | "[" string "]" } ;
 * primary  = number | string | true | false | null | "$" | "[" [ expr { "," expr } ] "]" | ident "(" [ arg { "," arg } ] ")" | ident | "(" expr ")" ;
 * arg      = [ ident "=" ] expr ;
 * </pre>
 *
 * A field is a bare name, a dotted model name ({@code trades.desk}) or {@code $.NAME}; all three read as one {@link Ast.Ref}. Errors
 * are {@link IllegalArgumentException}s whose words name the place (the engine turns them into {@code QRY-1003}).
 */
public final class Parser {

    /** The most nodes one expression may build: a bound, as Rachana's own parser has. */
    static final int MAX_DEPTH = 200;

    private final String src;
    private final List<Tok> toks;
    private int p;
    private int depth;

    private record Tok(char kind, String text, int at) {
        boolean is(char k, String t) {
            return kind == k && text.equals(t);
        }
    }

    private Parser(String src) {
        this.src = src;
        this.toks = lex(src);
    }

    /** Parses one expression; IllegalArgumentException in words. */
    public static Ast parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("an empty formula");
        }
        Parser ps = new Parser(text);
        Ast a = ps.expr();
        if (ps.peek().kind != 'E') {
            throw ps.error("unexpected '" + ps.peek().text + "'");
        }
        return a;
    }

    // ---- lexer: N number, S string, I identifier, O operator, E end

    private static List<Tok> lex(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isDigit(c) || c == '.' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1))) {
                int j = i;
                while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '_' || s.charAt(j) == '.')) {
                    j++;
                }
                if (j < s.length() && (s.charAt(j) == 'e' || s.charAt(j) == 'E')) {
                    int k = j + 1;
                    if (k < s.length() && (s.charAt(k) == '+' || s.charAt(k) == '-')) {
                        k++;
                    }
                    if (k < s.length() && Character.isDigit(s.charAt(k))) {
                        j = k;
                        while (j < s.length() && Character.isDigit(s.charAt(j))) {
                            j++;
                        }
                    }
                }
                out.add(new Tok('N', s.substring(i, j).replace("_", ""), i));
                i = j;
            } else if (c == '\'' || c == '"') {
                StringBuilder b = new StringBuilder();
                int j = i + 1;
                while (j < s.length() && s.charAt(j) != c) {
                    if (s.charAt(j) == '\\' && j + 1 < s.length()) {
                        j++;
                    }
                    b.append(s.charAt(j));
                    j++;
                }
                if (j >= s.length()) {
                    throw new IllegalArgumentException("a text that is not closed, at " + (i + 1) + " of: " + s);
                }
                out.add(new Tok('S', b.toString(), i));
                i = j + 1;
            } else if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
                    j++;
                }
                out.add(new Tok('I', s.substring(i, j), i));
                i = j;
            } else {
                String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
                if (List.of("&&", "||", "==", "!=", "<=", ">=").contains(two)) {
                    out.add(new Tok('O', two, i));
                    i += 2;
                } else if ("+-*/%<>!?:,()[].$=".indexOf(c) >= 0) {
                    out.add(new Tok('O', String.valueOf(c), i));
                    i++;
                } else {
                    throw new IllegalArgumentException("'" + c + "' is not part of a formula, at " + (i + 1) + " of: " + s);
                }
            }
        }
        out.add(new Tok('E', "the end", s.length()));
        return out;
    }

    // ---- parser

    private Tok peek() {
        return toks.get(p);
    }

    private Tok peek(int ahead) {
        return toks.get(Math.min(p + ahead, toks.size() - 1));
    }

    private boolean accept(char k, String t) {
        if (peek().is(k, t)) {
            p++;
            return true;
        }
        return false;
    }

    private void expect(String op) {
        if (!accept('O', op)) {
            throw error("'" + op + "' expected, not '" + peek().text + "'");
        }
    }

    private IllegalArgumentException error(String words) {
        return new IllegalArgumentException(words + ", at " + (peek().at + 1) + " of: " + src);
    }

    private Ast expr() {
        if (++depth > MAX_DEPTH) {
            throw error("the formula nests too deep");
        }
        try {
            Ast c = or();
            if (accept('O', "?")) {
                Ast a = expr();
                expect(":");
                Ast b = expr();
                return new Ast.Ternary(c, a, b);
            }
            return c;
        } finally {
            depth--;
        }
    }

    private Ast or() {
        Ast l = and();
        while (accept('O', "||") || accept('I', "or")) {
            l = new Ast.Binary("||", l, and());
        }
        return l;
    }

    private Ast and() {
        Ast l = not();
        while (accept('O', "&&") || accept('I', "and")) {
            l = new Ast.Binary("&&", l, not());
        }
        return l;
    }

    private Ast not() {
        if (peek().is('I', "not") && !peek(1).is('I', "in")) {
            p++;
            return new Ast.Unary("!", not());
        }
        return equality();
    }

    private Ast equality() {
        Ast l = compare();
        while (true) {
            if (accept('O', "==")) {
                l = new Ast.Binary("==", l, compare());
            } else if (accept('O', "!=")) {
                l = new Ast.Binary("!=", l, compare());
            } else {
                return l;
            }
        }
    }

    private Ast compare() {
        Ast l = sum();
        while (true) {
            Tok t = peek();
            if (t.kind == 'O' && List.of("<", "<=", ">", ">=").contains(t.text)) {
                p++;
                l = new Ast.Binary(t.text, l, sum());
            } else if (t.is('I', "in")) {
                p++;
                l = new Ast.In(l, sum(), false);
            } else if (t.is('I', "not") && peek(1).is('I', "in")) {
                p += 2;
                l = new Ast.In(l, sum(), true);
            } else {
                return l;
            }
        }
    }

    private Ast sum() {
        Ast l = product();
        while (true) {
            if (accept('O', "+")) {
                l = new Ast.Binary("+", l, product());
            } else if (accept('O', "-")) {
                l = new Ast.Binary("-", l, product());
            } else {
                return l;
            }
        }
    }

    private Ast product() {
        Ast l = unary();
        while (true) {
            Tok t = peek();
            if (t.kind == 'O' && List.of("*", "/", "%").contains(t.text)) {
                p++;
                l = new Ast.Binary(t.text, l, unary());
            } else {
                return l;
            }
        }
    }

    private Ast unary() {
        if (accept('O', "!")) {
            return new Ast.Unary("!", unary());
        }
        if (accept('O', "-")) {
            Ast x = unary();
            return x instanceof Ast.Num n ? new Ast.Num(-n.value(), n.whole()) : new Ast.Unary("-", x);
        }
        if (accept('O', "+")) {
            return unary();
        }
        return postfix();
    }

    private Ast postfix() {
        Ast a = primary();
        while (true) {
            if (peek().is('O', ".") && peek(1).kind == 'I' && a instanceof Ast.Ref r) {
                p++;
                String name = peek().text;
                p++;
                a = new Ast.Ref(r.name().isEmpty() ? name : r.name() + "." + name);
            } else if (peek().is('O', "[") && peek(1).kind == 'S' && peek(2).is('O', "]") && a instanceof Ast.Ref r) {
                p++;
                String name = peek().text;
                p += 2;
                a = new Ast.Ref(r.name().isEmpty() ? name : r.name() + "." + name);
            } else {
                if (a instanceof Ast.Ref r && r.name().isEmpty()) {
                    throw error("$ alone is the whole row: name a field ($.PNL)");
                }
                return a;
            }
        }
    }

    private Ast primary() {
        Tok t = peek();
        switch (t.kind) {
            case 'N' -> {
                p++;
                try {
                    boolean whole = !t.text.contains(".") && !t.text.contains("e") && !t.text.contains("E");
                    return new Ast.Num(Double.parseDouble(t.text), whole);
                } catch (NumberFormatException e) {
                    throw error("'" + t.text + "' is not a number");
                }
            }
            case 'S' -> {
                p++;
                return new Ast.Str(t.text);
            }
            case 'I' -> {
                p++;
                switch (t.text) {
                    case "true", "True" -> {
                        return new Ast.Bool(true);
                    }
                    case "false", "False" -> {
                        return new Ast.Bool(false);
                    }
                    case "null", "None" -> {
                        return new Ast.Null();
                    }
                    default -> { }
                }
                if (accept('O', "(")) {
                    return call(t.text);
                }
                return new Ast.Ref(t.text);
            }
            case 'O' -> {
                if (accept('O', "$")) {
                    return new Ast.Ref("");
                }
                if (accept('O', "(")) {
                    Ast a = expr();
                    expect(")");
                    return a;
                }
                if (accept('O', "[")) {
                    List<Ast> items = new ArrayList<>();
                    if (!accept('O', "]")) {
                        do {
                            if (peek().is('O', "]")) {
                                break;
                            }
                            items.add(expr());
                        } while (accept('O', ","));
                        expect("]");
                    }
                    return new Ast.ListOf(items);
                }
                throw error("a value expected, not '" + t.text + "'");
            }
            default -> throw error("the formula ends early: a value is missing");
        }
    }

    private Ast call(String name) {
        List<Ast> args = new ArrayList<>();
        Map<String, Ast> named = new LinkedHashMap<>();
        if (accept('O', ")")) {
            return new Ast.Call(name, args, named);
        }
        do {
            if (peek().kind == 'I' && peek(1).is('O', "=")) {
                String key = peek().text;
                p += 2;
                if (named.put(key, expr()) != null) {
                    throw error(key + " is named twice in " + name + "(...)");
                }
            } else {
                if (!named.isEmpty()) {
                    throw error("a positional argument after a named one in " + name + "(...)");
                }
                args.add(expr());
            }
        } while (accept('O', ","));
        expect(")");
        return new Ast.Call(name, args, named);
    }
}
