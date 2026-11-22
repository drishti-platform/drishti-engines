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

import com.ash.engine.Batch;
import com.ash.engine.Changes;
import com.ash.engine.Control;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.Filter;
import com.ash.engine.InputTable;
import com.ash.engine.Scan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The approved code a session holds: the rows of the {@code code} input, by name and version. Immutable; a change builds the next one
 * ({@link #with}), so an ask sees the code as it was or as it is, never between. Holds no viewer's data: code is the same for every
 * caller (what code may read is decided by the frames it is handed).
 */
final class ApprovedCode {

    static final List<String> REQUIRED = List.of("NAME", "VERSION", "HASH", "INTERFACE", "TEXT");
    static final List<String> OPTIONAL = List.of("ENTRY", "PACKAGES", "TIMEOUT_S", "MEMORY_MB");

    private final Map<String, TreeMap<String, CodeItem>> byName;
    private final Map<String, String> refused;

    private ApprovedCode(Map<String, TreeMap<String, CodeItem>> byName, Map<String, String> refused) {
        this.byName = byName;
        this.refused = refused;
    }

    static ApprovedCode empty() {
        return new ApprovedCode(Map.of(), Map.of());
    }

    /** Reads the table in batches (the host's {@code scan}); checks each row; a row that is not well formed is refused by name, not dropped. */
    static ApprovedCode read(InputTable table, Control control) throws EngineException {
        List<String> cols = new ArrayList<>(REQUIRED);
        OPTIONAL.stream().filter(c -> table.columns().contains(c)).forEach(cols::add);
        Map<String, TreeMap<String, CodeItem>> map = new HashMap<>();
        Map<String, String> refused = new HashMap<>();
        table.scan(new Scan(cols, Filter.ALL, 256), batch -> {
            control.check();
            add(map, refused, batch, table.columns());
        });
        return new ApprovedCode(map, refused);
    }

    private static void add(Map<String, TreeMap<String, CodeItem>> map, Map<String, String> refused, Batch b, java.util.Set<String> present) {
        String[] name = b.texts("NAME");
        String[] version = b.texts("VERSION");
        String[] hash = b.texts("HASH");
        String[] iface = b.texts("INTERFACE");
        String[] text = b.texts("TEXT");
        String[] entry = present.contains("ENTRY") ? b.texts("ENTRY") : null;
        String[] packages = present.contains("PACKAGES") ? b.texts("PACKAGES") : null;
        long[] timeout = present.contains("TIMEOUT_S") ? b.integers("TIMEOUT_S") : null;
        long[] memory = present.contains("MEMORY_MB") ? b.integers("MEMORY_MB") : null;
        for (int i = 0; i < b.rows(); i++) {
            String key = name[i] + "@" + version[i];
            if (name[i] == null || version[i] == null || text[i] == null || hash[i] == null || iface[i] == null) {
                refused.put(key, "its row lacks a name, version, hash, interface or text");
                continue;
            }
            if (!CodeItem.INTERFACES.contains(iface[i])) {
                refused.put(key, "its interface '" + iface[i] + "' is not one of " + CodeItem.INTERFACES);
                continue;
            }
            CodeItem item = new CodeItem(name[i], version[i], hash[i], text[i], iface[i], entry == null || entry[i] == null ? "" : entry[i],
                    packages == null ? List.of() : CodeItem.packages(packages[i]), timeout == null || b.isNull("TIMEOUT_S", i) ? 0 : (int) timeout[i],
                    memory == null || b.isNull("MEMORY_MB", i) ? 0 : (int) memory[i]);
            if (!item.intact()) {
                refused.put(key, "its text does not hash to its approved hash");
                continue;
            }
            map.computeIfAbsent(item.name(), n -> new TreeMap<>(CodeItem::compareVersions)).put(item.version(), item);
        }
    }

    /** The next state with {@code changes} to the {@code code} input applied (upserts replace by name and version; deletions are {@code name@version}). */
    ApprovedCode with(List<Changes> changes) {
        Map<String, TreeMap<String, CodeItem>> map = new HashMap<>();
        byName.forEach((k, v) -> map.put(k, new TreeMap<>(v)));
        Map<String, String> bad = new HashMap<>(refused);
        for (Changes c : changes) {
            for (String d : c.deletedKeys()) {
                int at = d.lastIndexOf('@');
                if (at > 0) {
                    TreeMap<String, CodeItem> versions = map.get(d.substring(0, at));
                    if (versions != null) {
                        versions.remove(d.substring(at + 1));
                        if (versions.isEmpty()) {
                            map.remove(d.substring(0, at));
                        }
                    }
                    bad.remove(d);
                }
            }
            if (c.upserts() != null && c.upserts().rows() > 0) {
                java.util.Set<String> present = new java.util.HashSet<>(REQUIRED);
                for (String o : OPTIONAL) {
                    try {
                        c.upserts().texts(o);
                        present.add(o);
                    } catch (RuntimeException absent) {
                        // an optional column not delivered
                    }
                }
                add(map, bad, c.upserts(), present);
            }
        }
        return new ApprovedCode(map, bad);
    }

    /** The item {@code name} at {@code version}, or its highest version when {@code version} is null or blank. */
    CodeItem find(String name, String version) throws EngineException {
        TreeMap<String, CodeItem> versions = byName.get(name);
        String want = version == null || version.isBlank() ? null : version;
        if (versions == null || want != null && !versions.containsKey(want)) {
            String id = name + (want == null ? "" : "@" + want);
            String why = refused.get(name + "@" + want);
            throw new EngineException(why != null && why.contains("hash") ? Codes.HASH : Codes.NOT_APPROVED, Kind.REFUSED,
                    why != null ? id + " is refused: " + why : "no approved code item " + id + " (approved: " + names() + ")");
        }
        return want == null ? versions.lastEntry().getValue() : versions.get(want);
    }

    /** The approved item names, sorted. */
    List<String> names() {
        return byName.keySet().stream().sorted().toList();
    }

    int count() {
        return byName.values().stream().mapToInt(Map::size).sum();
    }

    /** What the items hold, about: the text twice (UTF-16) and a small overhead per item. */
    long bytes() {
        long n = 0;
        for (TreeMap<String, CodeItem> v : byName.values()) {
            for (CodeItem c : v.values()) {
                n += 2L * c.text().length() + 512;
            }
        }
        return n;
    }

    Optional<String> refusal(String id) {
        return Optional.ofNullable(refused.get(id));
    }
}
