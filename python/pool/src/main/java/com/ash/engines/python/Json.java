/*
 * Drishti Engines · first-party engines by contract.
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
package com.ash.engines.python;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small strict JSON reader for the worker's control lines (the JDK only; writing is {@code Canonical.json}). Objects become
 * {@code LinkedHashMap}, arrays {@code ArrayList}, integers {@code Long}, other numbers {@code Double}, plus String, Boolean and null.
 */
final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /** The value of {@code text}; IllegalArgumentException (with the position) for text that is not one JSON value. */
    static Object read(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.i != text.length()) {
            throw j.bad("text after the value");
        }
        return v;
    }

    /** The object of {@code text}; IllegalArgumentException when it is not an object. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String text) {
        Object v = read(text);
        if (!(v instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("a JSON object was expected");
        }
        return (Map<String, Object>) v;
    }

    private IllegalArgumentException bad(String why) {
        return new IllegalArgumentException("JSON: " + why + " at " + i);
    }

    private void ws() {
        while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
    }

    private Object value() {
        if (i >= s.length()) {
            throw bad("the text ends early");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                return word("true", Boolean.TRUE);
            case 'f':
                return word("false", Boolean.FALSE);
            case 'n':
                return word("null", null);
            default:
                return number();
        }
    }

    private Object word(String w, Object v) {
        if (!s.startsWith(w, i)) {
            throw bad("unexpected text");
        }
        i += w.length();
        return v;
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') {
                throw bad("a key was expected");
            }
            String k = string();
            ws();
            if (i >= s.length() || s.charAt(i++) != ':') {
                throw bad("a colon was expected");
            }
            ws();
            m.put(k, value());
            ws();
            if (i >= s.length()) {
                throw bad("the object is not closed");
            }
            char c = s.charAt(i++);
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw bad("a comma or } was expected");
            }
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (s.charAt(i) == ']') {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            if (i >= s.length()) {
                throw bad("the array is not closed");
            }
            char c = s.charAt(i++);
            if (c == ']') {
                return l;
            }
            if (c != ',') {
                throw bad("a comma or ] was expected");
            }
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) {
                throw bad("the string is not closed");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> throw bad("an unknown escape");
            }
        }
    }

    private Object number() {
        int from = i;
        boolean real = false;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            real |= ".eE".indexOf(s.charAt(i)) >= 0;
            i++;
        }
        if (from == i) {
            throw bad("a value was expected");
        }
        String t = s.substring(from, i);
        try {
            return real ? (Object) Double.valueOf(t) : (Object) Long.valueOf(t);
        } catch (NumberFormatException e) {
            throw bad("a number was expected");
        }
    }
}
