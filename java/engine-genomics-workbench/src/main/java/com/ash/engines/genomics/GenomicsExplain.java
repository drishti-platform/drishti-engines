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

import com.ash.engine.Cell;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.Explanation;
import com.ash.engine.Step;
import com.ash.engine.Steps;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Why a measure of the workbench is what it is, as steps a reader can follow: the counts the data gives, then the one formula that makes the
 * figure from them (a share rounded to four decimals, a percentile read between the two nearest activities, rounded to three). Every
 * step's formula is Rachana-EL over the step's own given values and the earlier steps.
 */
final class GenomicsExplain {

    private GenomicsExplain() {
    }

    static Explanation of(GenomicsSession.Worked w, Cell cell) throws EngineException {
        String key = cell.output() + "." + cell.column();
        switch (key) {
            case "summary.RESPONSE_RATE": {
                Cohort.Summary s = w.summary();
                return share(cell, "Responders", s.responders(), "Samples of the selection", s.samples());
            }
            case "summary.ALTERED_RATE": {
                Cohort.Summary s = w.summary();
                return share(cell, "Samples with " + w.gene() + " altered", orZero(s.altered()), "Samples of the selection", s.samples());
            }
            case "summary.RESPONSE_IF_ALTERED": {
                Cohort.Summary s = w.summary();
                return share(cell, "Responders among the samples with " + w.gene() + " altered", orZero(s.respondersAltered()), "Samples with " + w.gene() + " altered", orZero(s.altered()));
            }
            case "summary.RESPONSE_IF_NOT": {
                Cohort.Summary s = w.summary();
                return share(cell, "Responders among the others", orZero(s.respondersNot()), "The other samples", s.samples() - orZero(s.altered()));
            }
            case "matrix-genes.PCT": {
                Cohort.GeneCount g = w.matrix().counts().stream().filter(x -> x.gene().equals(cell.key().get("GENE"))).findFirst()
                        .orElseThrow(() -> new EngineException("ENG-3005", Kind.REFUSED, "the gene " + cell.key().get("GENE") + " is not in this answer"));
                return share(cell, "Samples of the selection that carry " + g.gene() + " altered", g.samples(), "Samples of the selection", w.matrix().selected());
            }
            default:
                break;
        }
        if ("rounds".equals(cell.output())) {
            Designs.Round r = w.rounds().stream().filter(x -> String.valueOf(x.round()).equals(cell.key().get("ROUND"))).findFirst()
                    .orElseThrow(() -> new EngineException("ENG-3005", Kind.REFUSED, "the round " + cell.key().get("ROUND") + " is not in this answer"));
            switch (cell.column()) {
                case "LOW":
                    return percentile(cell, r, 0.05);
                case "Q1":
                    return percentile(cell, r, 0.25);
                case "MEDIAN":
                    return percentile(cell, r, 0.5);
                case "Q3":
                    return percentile(cell, r, 0.75);
                case "HIGH":
                    return percentile(cell, r, 0.95);
                case "BEST":
                    return best(cell, r);
                default:
                    break;
            }
        }
        throw new EngineException("ENG-3005", Kind.REFUSED, "only the measures of summary, matrix-genes and rounds, for a row of the answer, are explained");
    }

    private static int orZero(Integer i) {
        return i == null ? 0 : i;
    }

    /** A share: two counts and their ratio to four decimals. */
    private static Explanation share(Cell cell, String partWords, int part, String wholeWords, int whole) throws EngineException {
        if (whole == 0) {
            throw new EngineException("ENG-3005", Kind.REFUSED, "there is no share of nothing: " + wholeWords + " is 0");
        }
        double value = GenomicsSession.rate(part, whole);
        List<Step> steps = List.of(
                Steps.of("part", partWords, "$.n", Map.of("n", (long) part), (long) part),
                Steps.of("whole", wholeWords, "$.n", Map.of("n", (long) whole), (long) whole),
                Steps.of("share", "The share, to four decimals", "round(step.part / step.whole, 4)", Map.of(), value));
        return new Explanation(cell, value, steps);
    }

    private static List<Double> list(double[] v) {
        List<Double> out = new ArrayList<>(v.length);
        for (double d : v) {
            out.add(d);
        }
        return out;
    }

    /** A percentile of the round's sorted activities: read between the two nearest, to three decimals. */
    private static Explanation percentile(Cell cell, Designs.Round r, double p) {
        double[] v = r.sorted();
        double at = p * (v.length - 1);
        int lo = (int) Math.floor(at);
        int hi = (int) Math.ceil(at);
        double value = V.round(Designs.q(v, p), 3);
        List<Double> values = list(v);
        List<Step> steps = List.of(
                Steps.of("count", "How many designs the round has", "count($.values)", Map.of("values", values), (long) v.length),
                Steps.of("position", "Where the percentile falls among the sorted activities (0 is the least)", "$.p * (step.count - 1)", Map.of("p", p), at),
                Steps.of("lower", "The activity at or below it: its position, cut down", "floor(step.position)", Map.of(), (long) lo),
                Steps.of("upper", "The activity at or above it: its position, cut up", "ceiling(step.position)", Map.of(), (long) hi),
                Steps.of("percentile", "Between the two nearest activities, to three decimals",
                        "round($.values[step.lower] + ($.values[step.upper] - $.values[step.lower]) * (step.position - step.lower), 3)", Map.of("values", values), value));
        return new Explanation(cell, value, steps);
    }

    /** The best activity: the last of the sorted ones, to three decimals. */
    private static Explanation best(Cell cell, Designs.Round r) {
        double[] v = r.sorted();
        double value = V.round(v[v.length - 1], 3);
        List<Step> steps = List.of(
                Steps.of("count", "How many designs the round has", "count($.values)", Map.of("values", list(v)), (long) v.length),
                Steps.of("best", "The most active design, to three decimals", "round($.values[step.count - 1], 3)", Map.of("values", list(v)), value));
        return new Explanation(cell, value, steps);
    }
}
