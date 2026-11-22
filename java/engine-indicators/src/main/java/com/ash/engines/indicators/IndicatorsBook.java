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

import com.ash.engine.Table;
import com.ash.tools.xlsx.Cells;
import com.ash.tools.xlsx.Sheet;
import com.ash.tools.xlsx.Workbook;
import java.io.OutputStream;
import java.util.List;
import java.util.function.Function;

/**
 * The indicators' workbook: {@code Data} holds each series' years and values (two columns a series) and, for the ranks, the rank and the
 * count of each economy (values only); {@code Main} recomputes every figure with formulas over {@code Data} (the years of growth in cell
 * B1) and names the first row's cell of each measure {@code OUT_<OUTPUT>_<COLUMN>}. Written with the family's one .xlsx writer, the same bytes
 * for the same request.
 */
final class IndicatorsBook {

    private IndicatorsBook() {
    }

    private static final String[] SERIES = {"CHANGE", "PCT_CHANGE", "CAGR", "MEAN", "STDEV", "Z_SCORE"};

    static void write(OutputStream out, IndicatorsSession.Worked w, List<String> outputs, Function<String, Table> tableOf) {
        boolean series = outputs.contains("series");
        boolean ranks = outputs.contains("ranks");
        int s = series ? w.figures().size() : 0;
        try (Workbook wb = Workbook.to(out)) {
            Sheet data = wb.sheet("Data");
            int rows = 1;
            if (series) {
                for (Calc.Figures f : w.figures()) {
                    rows = Math.max(rows, f.series().n() + 1);
                }
            }
            if (ranks) {
                rows = Math.max(rows, w.ranks().size() + 1);
            }
            for (int r = 1; r <= rows; r++) {
                data.row(r);
                for (int k = 0; k < s; k++) {
                    Calc.Series x = w.figures().get(k).series();
                    if (r == 1) {
                        data.text(2 * k, x.economy() + " | " + x.indicator() + " | year", "header").text(2 * k + 1, "value", "header");
                    } else if (r - 2 < x.n()) {
                        data.number(2 * k, (long) x.years()[r - 2], "int").number(2 * k + 1, x.values()[r - 2], "decimal");
                    }
                }
                if (ranks) {
                    int rc = 2 * s + 1;
                    if (r == 1) {
                        data.text(rc, "INDICATOR | ECONOMY", "header").text(rc + 1, "rank", "header").text(rc + 2, "economies", "header");
                    } else if (r - 2 < w.ranks().size()) {
                        Calc.Rank x = w.ranks().get(r - 2);
                        data.text(rc, x.indicator() + " | " + x.economy(), "normal").number(rc + 1, (long) x.rank(), "int").number(rc + 2, (long) x.of(), "int");
                    }
                }
            }
            Sheet main = wb.sheet("Main");
            main.row(1).text(0, "Years of growth", "bold").number(1, (long) w.years(), "int");
            int row = 2;
            if (series) {
                Table t = tableOf.apply("series");
                row++;
                main.row(row).text(0, "ECONOMY", "header").text(1, "INDICATOR", "header").text(2, "N", "header").text(3, "LATEST", "header").text(4, "PREVIOUS", "header");
                for (int i = 0; i < SERIES.length; i++) {
                    main.text(5 + i, SERIES[i], "header");
                }
                int first = row + 1;
                for (int k = 0; k < s; k++) {
                    row++;
                    series(wb, main, t, k, row, first);
                }
            }
            if (ranks) {
                Table t = tableOf.apply("ranks");
                row += 2;
                main.row(row).text(0, "INDICATOR", "header").text(1, "ECONOMY", "header").text(2, "PERCENTILE", "header");
                int first = row + 1;
                for (int i = 0; i < w.ranks().size(); i++) {
                    row++;
                    Calc.Rank x = w.ranks().get(i);
                    int rc = 2 * s + 1;
                    main.row(row).text(0, x.indicator(), "normal").text(1, x.economy(), "normal");
                    if (x.percentile() != null) {
                        String rank = "Data!$" + Cells.col(rc + 1) + "$" + (i + 2);
                        String of = "Data!$" + Cells.col(rc + 2) + "$" + (i + 2);
                        main.formula(2, "(" + of + "-" + rank + ")/(" + of + "-1)*100", t.numbers("PERCENTILE")[i], "decimal");
                    }
                }
                if (!w.ranks().isEmpty() && w.ranks().get(0).percentile() != null) {
                    wb.name("OUT_RANKS_PERCENTILE", main.name(), "$C$" + first);
                }
            }
        }
    }

    /** One series' row: its counts and figures as formulas over its two Data columns, with the engine's results cached. */
    private static void series(Workbook wb, Sheet main, Table t, int k, int row, int first) {
        String yrs = "Data!$" + Cells.col(2 * k) + "$2:$" + Cells.col(2 * k) + "$" + (t.integers("N")[k] + 1);
        String val = "Data!$" + Cells.col(2 * k + 1) + "$2:$" + Cells.col(2 * k + 1) + "$" + (t.integers("N")[k] + 1);
        String n = "$C" + row;
        String latest = "$D" + row;
        String prev = "$E" + row;
        main.row(row).text(0, t.texts("ECONOMY")[k], "normal").text(1, t.texts("INDICATOR")[k], "normal")
                .formula(2, "COUNT(" + val + ")", (double) t.integers("N")[k], "int")
                .formula(3, "INDEX(" + val + "," + n + ")", t.numbers("LATEST")[k], "decimal");
        boolean two = !t.isNull("PREVIOUS", k);
        if (two) {
            main.formula(4, "INDEX(" + val + "," + n + "-1)", t.numbers("PREVIOUS")[k], "decimal");
        }
        String[] f = new String[6];
        f[0] = two ? latest + "-" + prev : null;
        f[1] = !t.isNull("PCT_CHANGE", k) ? "(" + latest + "-" + prev + ")/ABS(" + prev + ")*100" : null;
        f[2] = !t.isNull("CAGR", k) ? "((" + latest + "/INDEX(" + val + ",MATCH(INDEX(" + yrs + "," + n + ")-$B$1," + yrs + ",0)))^(1/$B$1)-1)*100" : null;
        f[3] = "AVERAGE(" + val + ")";
        f[4] = !t.isNull("STDEV", k) ? "SQRT(SUMPRODUCT((" + val + "-$I" + row + ")^2)/(" + n + "-1))" : null;
        f[5] = !t.isNull("Z_SCORE", k) ? "(" + latest + "-$I" + row + ")/$J" + row : null;
        for (int i = 0; i < 6; i++) {
            if (f[i] != null) {
                main.formula(5 + i, f[i], t.numbers(SERIES[i])[k], "decimal");
                if (k == 0) {
                    wb.name("OUT_SERIES_" + SERIES[i], main.name(), "$" + Cells.col(5 + i) + "$" + first);
                }
            }
        }
    }
}
