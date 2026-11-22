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
package com.ash.engines.genomics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The protein-design computations of the showcase: the quantiles of activity by round and the leaderboard of the best designs, over the
 * rows of one protein. Ties are broken by design id, so the answer does not depend on the order the rows arrive in.
 */
final class Designs {

    /** One round's activity: the sorted values (the quantiles are made from them) and its method. */
    record Round(int round, double[] sorted, String method) {
    }

    /** One leaderboard row. */
    record Entry(int rank, V.Row design, String changes) {
    }

    private Designs() {
    }

    /** The protein's row by its name or its gene's (case does not matter), or null. */
    static V.Row protein(List<V.Row> proteins, String id) {
        for (V.Row p : proteins) {
            if (V.str(p.get("PROTEIN")).equalsIgnoreCase(id) || V.str(p.get("GENE")).equalsIgnoreCase(id)) {
                return p;
            }
        }
        return null;
    }

    private static List<V.Row> of(List<V.Row> designs, String protein) {
        return designs.stream().filter(d -> V.str(d.get("PROTEIN")).equals(protein)).toList();
    }

    /** Each round above 0 with a measured activity: its sorted activities and its method. */
    static List<Round> rounds(List<V.Row> designs, List<V.Row> roundRows, String protein) {
        Map<Integer, List<Double>> by = new TreeMap<>();
        for (V.Row d : of(designs, protein)) {
            double a = V.num(d.get("ACTIVITY"));
            int r = V.integer(d.get("ROUND"), 0);
            if (!Double.isNaN(a) && r > 0) {
                by.computeIfAbsent(r, k -> new ArrayList<>()).add(a);
            }
        }
        Map<Integer, String> method = new HashMap<>();
        if (roundRows != null) {
            roundRows.stream().filter(r -> V.str(r.get("PROTEIN")).equals(protein)).forEach(r -> method.put(V.integer(r.get("ROUND"), 0), V.str(r.get("METHOD"))));
        }
        List<Round> out = new ArrayList<>();
        by.forEach((r, xs) -> out.add(new Round(r, xs.stream().mapToDouble(Double::doubleValue).sorted().toArray(), method.getOrDefault(r, ""))));
        return out;
    }

    /** A quantile of sorted values (linear between the two nearest). */
    static double q(double[] sorted, double p) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        double at = p * (sorted.length - 1);
        int lo = (int) Math.floor(at);
        int hi = (int) Math.ceil(at);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (at - lo);
    }

    /** The best designs up to a round (0: every round), each with its changes. */
    static List<Entry> leaderboard(List<V.Row> designs, List<V.Row> mutations, String protein, int upTo, int n) {
        int limit = upTo <= 0 ? Integer.MAX_VALUE : upTo;
        List<V.Row> top = of(designs, protein).stream().filter(d -> V.integer(d.get("ROUND"), 0) > 0 && V.integer(d.get("ROUND"), 0) <= limit)
                .sorted(Comparator.comparingDouble((V.Row d) -> -V.num(d.get("ACTIVITY"))).thenComparing(d -> V.str(d.get("DESIGN_ID"))))
                .limit(Math.max(1, Math.min(200, n))).toList();
        Map<String, List<String>> changes = new HashMap<>();
        if (mutations != null) {
            var ids = new java.util.HashSet<String>();
            top.forEach(d -> ids.add(V.str(d.get("DESIGN_ID"))));
            mutations.stream().filter(m -> ids.contains(V.str(m.get("DESIGN_ID"))))
                    .sorted(Comparator.comparing((V.Row m) -> V.str(m.get("DESIGN_ID"))).thenComparingDouble(m -> V.num(m.get("POS"))).thenComparing(Designs::mutation))
                    .forEach(m -> changes.computeIfAbsent(V.str(m.get("DESIGN_ID")), k -> new ArrayList<>()).add(mutation(m)));
        }
        List<Entry> out = new ArrayList<>();
        int rank = 1;
        for (V.Row d : top) {
            out.add(new Entry(rank++, d, String.join(", ", changes.getOrDefault(V.str(d.get("DESIGN_ID")), List.of()))));
        }
        return out;
    }

    private static String mutation(V.Row m) {
        String t = V.str(m.get("MUTATION"));
        return t.isEmpty() ? V.str(m.get("AA_FROM")) + V.integer(m.get("POS"), 0) + V.str(m.get("AA_TO")) : t;
    }
}
