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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Cell coordinates in canonical text, as Drishti's {@code CellKey.canonical()}: {@code DIM=member} pairs sorted by dimension id and
 * joined by {@code |}, {@code *} for none. A member that holds {@code |}, {@code =} or {@code \} is escaped with {@code \}, so the text
 * reads back to the same pairs.
 */
public final class Keys {

    /** The coordinates of a total along an axis. */
    public static final String ALL = "*";

    private Keys() {}

    /** The canonical text of some coordinates. */
    public static String of(Map<String, String> coords) {
        if (coords.isEmpty()) {
            return ALL;
        }
        StringBuilder b = new StringBuilder();
        new TreeMap<>(coords).forEach((k, v) -> b.append(b.isEmpty() ? "" : "|").append(escape(k)).append('=').append(escape(v)));
        return b.toString();
    }

    /** The coordinates a canonical text names. */
    public static SortedMap<String, String> read(String text) {
        SortedMap<String, String> out = new TreeMap<>();
        if (text == null || text.equals(ALL) || text.isEmpty()) {
            return out;
        }
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                cur.append(c).append(text.charAt(++i));
            } else if (c == '|') {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString());
        for (String p : parts) {
            int eq = -1;
            for (int i = 0; i < p.length(); i++) {
                if (p.charAt(i) == '\\') {
                    i++;
                } else if (p.charAt(i) == '=') {
                    eq = i;
                    break;
                }
            }
            if (eq < 0) {
                throw new IllegalArgumentException("not cell coordinates: " + text);
            }
            out.put(unescape(p.substring(0, eq)), unescape(p.substring(eq + 1)));
        }
        return out;
    }

    static String escape(String s) {
        if (s.indexOf('|') < 0 && s.indexOf('=') < 0 && s.indexOf('\\') < 0) {
            return s;
        }
        return s.replace("\\", "\\\\").replace("|", "\\|").replace("=", "\\=");
    }

    static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                c = s.charAt(++i);
            }
            b.append(c);
        }
        return b.toString();
    }
}
