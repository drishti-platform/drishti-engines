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
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which samples a request is about (the showcase's selection): a cohort, then optionally clusters ({@code C3,C1}), a rectangle on the map of
 * samples ({@code x0,x1,y0,y1}) or named samples (at most 3,000). A selection is a filter of the samples, never a recount elsewhere.
 *
 * @param cohort the cohort ({@code ALL} or empty: every sample)
 * @param clusters the clusters (empty: any)
 * @param box x0, x1, y0, y1 or null
 * @param samples the samples named (empty: any)
 */
record Sel(String cohort, Set<String> clusters, double[] box, Set<String> samples) {

    static final int MOST_NAMED = 3000;

    /** Reads the request's parameters (blank ones are none); one that does not read is a message for the refusal. */
    static Sel of(String cohort, String cluster, String box, String samples) {
        double[] b = null;
        if (box != null && !box.isBlank()) {
            String[] p = box.split(",");
            if (p.length != 4) {
                throw new IllegalArgumentException("box is x0,x1,y0,y1 (four numbers), not " + box);
            }
            b = new double[4];
            for (int i = 0; i < 4; i++) {
                b[i] = V.num(p[i]);
                if (Double.isNaN(b[i])) {
                    throw new IllegalArgumentException("box is x0,x1,y0,y1 (four numbers), not " + box);
                }
            }
            b = new double[] {Math.min(b[0], b[1]), Math.max(b[0], b[1]), Math.min(b[2], b[3]), Math.max(b[2], b[3])};
        }
        Set<String> named = list(samples);
        if (named.size() > MOST_NAMED) {
            throw new IllegalArgumentException("at most " + MOST_NAMED + " samples are named in one request (" + named.size() + " were): send a cluster or a box");
        }
        return new Sel(cohort == null ? "" : cohort.trim(), list(cluster), b, named);
    }

    /** Whether anything narrows the cohort. */
    boolean narrowed() {
        return !clusters.isEmpty() || box != null || !samples.isEmpty();
    }

    /** The same cohort with nothing narrowing it. */
    Sel wholeCohort() {
        return new Sel(cohort, Set.of(), null, Set.of());
    }

    /** Whether a sample's row is a member. */
    boolean has(V.Row s) {
        if (!cohort.isEmpty() && !cohort.equalsIgnoreCase("ALL") && !cohort.equalsIgnoreCase(V.str(s.get("COHORT")))) {
            return false;
        }
        if (!clusters.isEmpty() && !clusters.contains(V.str(s.get("CLUSTER")))) {
            return false;
        }
        if (!samples.isEmpty() && !samples.contains(V.str(s.get("SAMPLE_ID")))) {
            return false;
        }
        if (box != null) {
            double x = V.num(s.get("EMB_X"));
            double y = V.num(s.get("EMB_Y"));
            return x >= box[0] && x <= box[1] && y >= box[2] && y <= box[3];
        }
        return true;
    }

    /** The selection in words ("cohort ONC-1, cluster C3 immune-hot, a rectangle on the map of samples"). */
    String words() {
        List<String> w = new ArrayList<>();
        w.add(cohort.isEmpty() || cohort.equalsIgnoreCase("ALL") ? "every cohort" : "cohort " + cohort);
        if (!clusters.isEmpty()) {
            w.add((clusters.size() == 1 ? "cluster " : "clusters ") + String.join(", ", new TreeSet<>(clusters)));
        }
        if (box != null) {
            w.add("a rectangle on the map of samples");
        }
        if (!samples.isEmpty()) {
            w.add(samples.size() + " named samples");
        }
        return String.join(", ", w);
    }

    private static Set<String> list(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s != null) {
            Arrays.stream(s.split(",")).map(String::trim).filter(p -> !p.isEmpty()).forEach(out::add);
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Sel x && cohort.equals(x.cohort) && clusters.equals(x.clusters) && Arrays.equals(box, x.box) && samples.equals(x.samples);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(cohort, clusters, Arrays.hashCode(box), samples);
    }

    @Override
    public String toString() {
        return words().toLowerCase(Locale.ROOT);
    }
}
