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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The indicators' arithmetic, in one place: the figures of a series and the ranks of an indicator. Plain IEEE doubles in a fixed order
 * (years ascending, left to right), the power by {@link StrictMath#pow}, so two machines give the same bits. A figure that cannot be made
 * (no year before, a zero to divide by, a growth from a value that is not positive) is null, never a made-up number.
 */
final class Calc {

    /** One series: an economy's indicator, its years ascending and the value of each. */
    record Series(String economy, String indicator, String unit, int[] years, double[] values) {
        int n() {
            return years.length;
        }

        double latest() {
            return values[values.length - 1];
        }

        int latestYear() {
            return years[years.length - 1];
        }
    }

    /** The figures of one series. Nullable ones are null when they cannot be made. */
    record Figures(Series series, Double previous, Double change, Double pct, Double start, Double cagr, double mean, Double stdev, Double z) {
    }

    /** One economy's place among the economies of an indicator. */
    record Rank(String indicator, String economy, int rank, int of, double latest, Double percentile) {
    }

    private Calc() {
    }

    /** The series a set of observations makes: grouped by economy and indicator, years ascending; a year given twice keeps the larger value. */
    static List<Series> series(List<Object[]> observations) {
        Map<String, Map<Integer, Double>> by = new TreeMap<>();
        Map<String, String[]> names = new TreeMap<>();
        for (Object[] o : observations) {                               // economy, indicator, year, value, unit
            String key = o[0] + "\u0000" + o[1];
            by.computeIfAbsent(key, k -> new TreeMap<>()).merge((Integer) o[2], (Double) o[3], Math::max);
            String[] n = names.computeIfAbsent(key, k -> new String[] {(String) o[0], (String) o[1], ""});
            if (!((String) o[4]).isEmpty() && (n[2].isEmpty() || ((String) o[4]).compareTo(n[2]) < 0)) {
                n[2] = (String) o[4];
            }
        }
        List<Series> out = new ArrayList<>();
        by.forEach((key, years) -> {
            int[] y = new int[years.size()];
            double[] v = new double[years.size()];
            int i = 0;
            for (Map.Entry<Integer, Double> e : years.entrySet()) {
                y[i] = e.getKey();
                v[i++] = e.getValue();
            }
            String[] n = names.get(key);
            out.add(new Series(n[0], n[1], n[2], y, v));
        });
        return out;
    }

    /** The figures of a series; {@code years} is the span of the compound growth, back from the latest year. */
    static Figures figures(Series s, int years) {
        int n = s.n();
        double latest = s.latest();
        Double previous = n >= 2 ? s.values()[n - 2] : null;
        Double change = previous == null ? null : latest - previous;
        Double pct = previous == null || previous == 0 ? null : (latest - previous) / Math.abs(previous) * 100;
        Double start = null;
        for (int i = 0; i < n; i++) {
            if (s.years()[i] == s.latestYear() - years) {
                start = s.values()[i];
            }
        }
        Double cagr = start == null || start <= 0 || latest <= 0 ? null : (StrictMath.pow(latest / start, 1.0 / years) - 1) * 100;
        double sum = 0;
        for (double v : s.values()) {
            sum += v;
        }
        double mean = sum / n;
        Double stdev = null;
        Double z = null;
        if (n >= 2) {
            double sq = 0;
            for (double v : s.values()) {
                sq += (v - mean) * (v - mean);
            }
            stdev = Math.sqrt(sq / (n - 1));
            z = stdev > 0 ? (latest - mean) / stdev : null;
        }
        return new Figures(s, previous, change, pct, start, cagr, mean, stdev, z);
    }

    /** The ranks of every indicator among the given series: largest latest value first, equal values sharing the better rank. */
    static List<Rank> ranks(List<Series> all) {
        Map<String, List<Series>> by = new TreeMap<>();
        all.forEach(s -> by.computeIfAbsent(s.indicator(), k -> new ArrayList<>()).add(s));
        List<Rank> out = new ArrayList<>();
        by.forEach((indicator, list) -> {
            List<Series> sorted = new ArrayList<>(list);
            sorted.sort(Comparator.comparingDouble((Series s) -> -s.latest()).thenComparing(Series::economy));
            int of = sorted.size();
            int rank = 0;
            for (int i = 0; i < of; i++) {
                if (i == 0 || sorted.get(i).latest() != sorted.get(i - 1).latest()) {
                    rank = i + 1;
                }
                Double pctile = of == 1 ? null : (double) (of - rank) / (of - 1) * 100;
                out.add(new Rank(indicator, sorted.get(i).economy(), rank, of, sorted.get(i).latest(), pctile));
            }
        });
        return out;
    }
}
