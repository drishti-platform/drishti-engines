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

import com.ash.engine.text.Canonical;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * One approved code item: a row of the contract {@code platform/code-item@1}. The host binds the {@code code} input to its approval
 * store, so only approved code is ever a row; the engine still holds the text to its hash (a text that does not hash to its approved
 * hash is refused, never run).
 *
 * @param name the item's name
 * @param version the approved version (numeric-aware order: 1.10 is after 1.9)
 * @param hash SHA-384 of the text's UTF-8 bytes, lower-case hex
 * @param text the code
 * @param iface {@code measure}, {@code column}, {@code transform}, {@code visual}, {@code cell} or {@code function}
 * @param entry the entry function or, for a function, its class; empty: the interface's default
 * @param packages the packages the code may import (a subset of the pool's allow-list)
 * @param timeoutSeconds the item's own CPU time, at most the pool's (0: the pool's)
 * @param memoryMb the item's own memory, at most the pool's (0: the pool's)
 */
record CodeItem(String name, String version, String hash, String text, String iface, String entry, List<String> packages, int timeoutSeconds,
        int memoryMb) {

    static final List<String> INTERFACES = List.of("measure", "column", "transform", "visual", "cell", "function");

    CodeItem {
        entry = entry == null ? "" : entry;
        packages = List.copyOf(packages == null ? List.of() : packages);
    }

    /** True when the text hashes to the approved hash. */
    boolean intact() {
        return Canonical.sha384(text).equalsIgnoreCase(hash);
    }

    /** {@code name@version}. */
    String id() {
        return name + "@" + version;
    }

    /** The item's fields as the worker's {@code code} parameter. */
    Map<String, Object> wire() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", name);
        m.put("version", version);
        m.put("hash", hash);
        m.put("text", text);
        m.put("interface", iface);
        m.put("entry", entry);
        m.put("packages", packages);
        m.put("timeout_s", timeoutSeconds);
        m.put("memory_mb", memoryMb);
        return m;
    }

    /** Packages of a {@code PACKAGES} cell: comma or space separated names. */
    static List<String> packages(String cell) {
        if (cell == null || cell.isBlank()) {
            return List.of();
        }
        return Arrays.stream(cell.split("[,\\s]+")).filter(s -> !s.isBlank()).toList();
    }

    /** Numeric-aware version order: {@code 1.10} after {@code 1.9}; other parts compare as text. */
    static int compareVersions(String a, String b) {
        String[] x = a.split("[.-]");
        String[] y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "0";
            String q = i < y.length ? y[i] : "0";
            int c = p.matches("\\d+") && q.matches("\\d+") ? Long.compare(Long.parseLong(p), Long.parseLong(q)) : p.compareTo(q);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
