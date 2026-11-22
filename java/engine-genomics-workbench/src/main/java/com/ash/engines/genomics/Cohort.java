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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The cohort computations of the showcase, over the rows a request may see: which samples carry which gene altered (a coding change in a
 * call, or a copy-number segment over the gene), the figures of a selection, and the cohort matrix. The arithmetic and the orders are the
 * showcase's own, with every tie broken by name so that the answer does not depend on the order the rows arrive in.
 */
final class Cohort {

    static final List<String> ALTERATIONS = List.of("truncating", "splice", "missense", "inframe", "amplification", "deep deletion");

    /** The figures of a selection (counts; the rates are made from them). {@code altered} etc. are null when no gene was asked. */
    record Summary(String cohort, String words, int cohortSamples, int samples, int responders, Integer altered, Integer respondersAltered, Integer respondersNot) {
    }

    /** One altered cell of the matrix. */
    record Cell(String sample, String gene, String alteration) {
    }

    /** A gene of the matrix: how many of the selection's samples carry it. */
    record GeneCount(String gene, int samples) {
    }

    /** The matrix: genes in order, samples in the mutual-exclusivity order, the altered cells, the gene counts. */
    record Matrix(List<String> genes, List<String> samples, List<Cell> cells, List<GeneCount> counts, int selected) {
    }

    private final Map<String, V.Row> samples = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> cells = new HashMap<>();       // sample -> gene -> the alteration shown (the worst)
    private final Map<String, Set<String>> altered = new HashMap<>();             // gene -> the samples with an alteration in it
    private final Set<String> geneNames = new HashSet<>();

    /** Builds the index from the rows; the optional tables are null when the input is absent. */
    Cohort(List<V.Row> sampleRows, List<V.Row> calls, List<V.Row> variantRows, List<V.Row> geneRows, List<V.Row> cnv) {
        sampleRows.forEach(r -> samples.put(V.str(r.get("SAMPLE_ID")), r));
        Map<String, V.Row> variants = new HashMap<>();
        variantRows.forEach(r -> variants.put(V.str(r.get("VARIANT_ID")), r));
        for (V.Row c : calls) {
            V.Row v = variants.get(V.str(c.get("VARIANT_ID")));
            String alt = v == null ? null : alteration(V.str(v.get("CONSEQUENCE")));
            if (alt != null) {
                cells.computeIfAbsent(V.str(c.get("SAMPLE_ID")), k -> new HashMap<>()).merge(V.str(v.get("GENE")), alt, Cohort::worse);
            }
        }
        Map<String, List<V.Row>> genesByChrom = new HashMap<>();
        if (geneRows != null) {
            geneRows.forEach(g -> {
                geneNames.add(V.str(g.get("GENE")));
                genesByChrom.computeIfAbsent(V.str(g.get("CHROM")), k -> new ArrayList<>()).add(g);
            });
        }
        if (cnv != null) {
            for (V.Row s : cnv) {
                String call = copyCall(s);
                if (call == null) {
                    continue;
                }
                double a = V.num(s.get("START"));
                double b = V.num(s.get("END"));
                for (V.Row g : genesByChrom.getOrDefault(V.str(s.get("CHROM")), List.of())) {
                    if (V.num(g.get("START")) <= b && V.num(g.get("END")) >= a) {
                        cells.computeIfAbsent(V.str(s.get("SAMPLE_ID")), k -> new HashMap<>()).merge(V.str(g.get("GENE")), call, Cohort::worse);
                    }
                }
            }
        }
        cells.forEach((sid, m) -> m.keySet().forEach(g -> altered.computeIfAbsent(g, k -> new HashSet<>()).add(sid)));
    }

    /** The alteration a consequence shows in the matrix (null for a silent or non-coding one). */
    static String alteration(String consequence) {
        String c = consequence.toLowerCase(Locale.ROOT);
        if (c.contains("stop_gained") || c.contains("frameshift") || c.contains("nonsense") || c.contains("truncat") || c.contains("start_lost")) {
            return "truncating";
        }
        if (c.contains("splice")) {
            return "splice";
        }
        if (c.contains("missense")) {
            return "missense";
        }
        if (c.contains("inframe")) {
            return "inframe";
        }
        return null;
    }

    /** A copy-number segment's call in the matrix: amplification (AMP, or five copies and more) or deep deletion (DEL with no copy left). */
    static String copyCall(V.Row s) {
        String call = V.str(s.get("CALL")).toUpperCase(Locale.ROOT);
        double cn = V.num(s.get("COPY_NUMBER"));
        if (call.startsWith("AMP") || (!Double.isNaN(cn) && cn >= 5)) {
            return "amplification";
        }
        if ((call.startsWith("DEL") || call.contains("DEEP")) && (Double.isNaN(cn) || cn < 0.5)) {
            return "deep deletion";
        }
        return null;
    }

    private static String worse(String a, String b) {
        return ALTERATIONS.indexOf(a) <= ALTERATIONS.indexOf(b) ? a : b;
    }

    private Set<String> members(Sel sel) {
        Set<String> out = new LinkedHashSet<>();
        samples.values().stream().filter(sel::has).forEach(s -> out.add(V.str(s.get("SAMPLE_ID"))));
        return out;
    }

    private long responders(Iterable<String> ids) {
        long n = 0;
        for (String s : ids) {
            if (V.responded(samples.get(s).get("RESPONSE"))) {
                n++;
            }
        }
        return n;
    }

    /** The figures of a selection and, when {@code gene} is not blank, of the gene's alteration within it. */
    Summary summary(Sel sel, String gene) {
        Set<String> m = members(sel);
        int all = sel.narrowed() ? members(sel.wholeCohort()).size() : m.size();
        int resp = (int) responders(m);
        if (gene == null || gene.isBlank()) {
            return new Summary(sel.cohort(), sel.words(), all, m.size(), resp, null, null, null);
        }
        Set<String> alt = altered.getOrDefault(gene, Set.of());
        List<String> yes = m.stream().filter(alt::contains).toList();
        List<String> no = m.stream().filter(s -> !alt.contains(s)).toList();
        return new Summary(sel.cohort(), sel.words(), all, m.size(), resp, yes.size(), (int) responders(yes), (int) responders(no));
    }

    /** The cohort matrix: the most altered genes (and the one asked for), the samples sorted so each gene's carriers sit together. */
    Matrix matrix(Sel sel, int genesWanted, String gene) {
        Set<String> m = members(sel);
        int n = Math.max(1, Math.min(60, genesWanted));
        List<String> genes = new ArrayList<>(altered.keySet().stream()
                .sorted(Comparator.comparingLong((String g) -> -altered.get(g).stream().filter(m::contains).count()).thenComparing(g -> g)).limit(n).toList());
        if (gene != null && !gene.isBlank() && !genes.contains(gene) && geneNames.contains(gene)) {
            genes.add(0, gene);
        }
        List<String> order = new ArrayList<>(m);
        Map<String, Long> mask = new HashMap<>();
        for (String s : order) {
            long k = 0;
            Map<String, String> row = cells.getOrDefault(s, Map.of());
            for (int i = 0; i < Math.min(62, genes.size()); i++) {
                if (row.containsKey(genes.get(i))) {
                    k |= 1L << (62 - i);
                }
            }
            mask.put(s, k);
        }
        order.sort(Comparator.comparingLong((String s) -> -mask.get(s)).thenComparing(s -> s));
        List<Cell> out = new ArrayList<>();
        for (String s : order) {
            Map<String, String> row = cells.getOrDefault(s, Map.of());
            for (String g : genes) {
                String a = row.get(g);
                if (a != null) {
                    out.add(new Cell(s, g, a));
                }
            }
        }
        List<GeneCount> counts = new ArrayList<>();
        for (String g : genes) {
            counts.add(new GeneCount(g, (int) altered.getOrDefault(g, Set.of()).stream().filter(m::contains).count()));
        }
        return new Matrix(genes, order, out, counts, m.size());
    }
}
