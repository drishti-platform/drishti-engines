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
package com.ash.engines.indicators;

import com.ash.engine.Cell;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.Explanation;
import com.ash.engine.Step;
import com.ash.engine.Steps;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Why an indicator figure is what it is: the observations it is made from, then each formula over them as a Rachana-EL step. */
final class IndicatorsExplain {

    private IndicatorsExplain() {
    }

    private static List<Double> list(double[] v) {
        List<Double> out = new ArrayList<>(v.length);
        for (double d : v) {
            out.add(d);
        }
        return out;
    }

    private static EngineException refused(String words) {
        return new EngineException("ENG-3005", Kind.REFUSED, words);
    }

    static Explanation of(IndicatorsSession.Worked w, Cell cell) throws EngineException {
        if ("ranks".equals(cell.output()) && "PERCENTILE".equals(cell.column())) {
            Calc.Rank r = w.ranks().stream().filter(x -> x.indicator().equals(cell.key().get("INDICATOR")) && x.economy().equals(cell.key().get("ECONOMY"))).findFirst()
                    .orElseThrow(() -> refused("that economy is not ranked in this answer"));
            if (r.percentile() == null) {
                throw refused("an economy alone in its indicator has no percentile");
            }
            return new Explanation(cell, r.percentile(), List.of(
                    Steps.of("rank", "The rank by latest value, largest first", "$.n", Map.of("n", (long) r.rank()), (long) r.rank()),
                    Steps.of("of", "How many economies are ranked", "$.n", Map.of("n", (long) r.of()), (long) r.of()),
                    Steps.of("percentile", "The share of the other economies below it, in percent", "(step.of - step.rank) / (step.of - 1) * 100", Map.of(), r.percentile())));
        }
        Calc.Figures f = "series".equals(cell.output()) ? w.figures().stream()
                .filter(x -> x.series().economy().equals(cell.key().get("ECONOMY")) && x.series().indicator().equals(cell.key().get("INDICATOR"))).findFirst()
                .orElseThrow(() -> refused("that series is not in this answer")) : null;
        if (f == null) {
            throw refused("only the measures of series and ranks, for a row of the answer, are explained");
        }
        Calc.Series s = f.series();
        Step latest = Steps.of("latest", "The latest value (" + s.latestYear() + ")", "$.value", Map.of("value", s.latest()), s.latest());
        Step values = Steps.of("history", "How many yearly values the series has", "count($.values)", Map.of("values", list(s.values())), (long) s.n());
        switch (cell.column()) {
            case "CHANGE": {
                Step prev = previous(f);
                return new Explanation(cell, f.change(), List.of(latest, prev, Steps.of("change", "The latest value less the one before", "step.latest - step.previous", Map.of(), f.change())));
            }
            case "PCT_CHANGE": {
                Step prev = previous(f);
                if (f.pct() == null) {
                    throw refused("no change in percent: the earlier value is 0");
                }
                return new Explanation(cell, f.pct(), List.of(latest, prev,
                        Steps.of("pct", "The change as a percentage of the earlier value", "(step.latest - step.previous) / abs(step.previous) * 100", Map.of(), f.pct())));
            }
            case "CAGR": {
                if (f.cagr() == null) {
                    throw refused("no compound growth for this series: no value " + w.years() + " years back, or a value that is not positive");
                }
                return new Explanation(cell, f.cagr(), List.of(latest,
                        Steps.of("start", "The value " + w.years() + " years before the latest", "$.value", Map.of("value", f.start()), f.start()),
                        Steps.of("cagr", "The yearly rate that grows the start value into the latest, in percent",
                                "(power(step.latest / step.start, 1 / $.years) - 1) * 100", Map.of("years", (long) w.years()), f.cagr())));
            }
            case "MEAN":
                return new Explanation(cell, f.mean(), List.of(values, Steps.of("mean", "The mean of the series", "avg($.values)", Map.of("values", list(s.values())), f.mean())));
            case "STDEV": {
                if (f.stdev() == null) {
                    throw refused("a series of one value has no standard deviation");
                }
                return new Explanation(cell, f.stdev(), List.of(values, Steps.of("stdev", "The sample standard deviation of the series", "stddev($.values)", Map.of("values", list(s.values())), f.stdev())));
            }
            case "Z_SCORE": {
                if (f.z() == null) {
                    throw refused("no z-score: the series has fewer than two values or does not vary");
                }
                return new Explanation(cell, f.z(), List.of(latest,
                        Steps.of("mean", "The mean of the series", "avg($.values)", Map.of("values", list(s.values())), f.mean()),
                        Steps.of("stdev", "The sample standard deviation of the series", "stddev($.values)", Map.of("values", list(s.values())), f.stdev()),
                        Steps.of("z", "How many standard deviations the latest value stands from the mean", "(step.latest - step.mean) / step.stdev", Map.of(), f.z())));
            }
            default:
                throw refused("only the measures of series and ranks are explained");
        }
    }

    private static Step previous(Calc.Figures f) throws EngineException {
        if (f.previous() == null) {
            throw refused("a series of one value has no change");
        }
        return Steps.of("previous", "The value before the latest", "$.value", Map.of("value", f.previous()), f.previous());
    }
}
