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

import com.ash.engine.Table;
import com.ash.tools.xlsx.Cells;
import com.ash.tools.xlsx.Sheet;
import com.ash.tools.xlsx.Workbook;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The workbench's workbook: {@code Data} holds the counts and the sorted activities the answer was made from (values only); {@code Main}
 * recomputes each measure with formulas over {@code Data} (a share rounded to four decimals; a percentile read between the two nearest
 * activities with SMALL, rounded to three) and names the first row's cell of each measure {@code OUT_<OUTPUT>_<COLUMN>}. Written with the
 * family's one .xlsx writer, the same bytes for the same request.
 */
final class GenomicsBook {

    private GenomicsBook() {
    }

    /** A formula and the result the engine computed (cached, so a reader that does not recalculate sees it). */
    private record F(String formula, double cached) {
    }

    private static final class Plan {
        final TreeMap<Integer, TreeMap<Integer, Object>> data = new TreeMap<>();
        final List<Object[]> main = new ArrayList<>();
        final List<String[]> names = new ArrayList<>();            // name, sheet, ref
        int dataRow = 1;

        /** A value in column B of the next Data row, named in column A; the cell as a formula writes it. */
        String scalar(String label, double v) {
            dataRow++;
            data.computeIfAbsent(dataRow, k -> new TreeMap<>()).put(0, label);
            data.get(dataRow).put(1, v);
            return "Data!$B$" + dataRow;
        }

        int row(Object... cells) {
            main.add(cells);
            return main.size();
        }

        void name(String name, int col, int row) {
            names.add(new String[] {name, "Main", "$" + Cells.col(col) + "$" + row});
        }
    }

    static void write(OutputStream out, GenomicsSession.Worked w, List<String> outputs, Function<String, Table> tableOf) {
        Plan p = new Plan();
        p.data.computeIfAbsent(1, k -> new TreeMap<>()).put(0, "ITEM");
        p.data.get(1).put(1, "VALUE");
        if (outputs.contains("summary") && w.summary() != null) {
            summary(p, w.summary(), tableOf.apply("summary"));
        }
        if (outputs.contains("matrix-genes") && w.matrix() != null) {
            genes(p, w.matrix(), tableOf.apply("matrix-genes"));
        }
        if (outputs.contains("rounds") && w.rounds() != null) {
            rounds(p, w.rounds(), tableOf.apply("rounds"));
        }
        try (Workbook wb = Workbook.to(out)) {
            Sheet data = wb.sheet("Data");
            p.data.forEach((r, cols) -> {
                data.row(r);
                cols.forEach((c, v) -> {
                    if (v instanceof Double d) {
                        data.number(c, d, "decimal");
                    } else {
                        data.text(c, String.valueOf(v), r == 1 ? "header" : "normal");
                    }
                });
            });
            Sheet main = wb.sheet("Main");
            if (p.main.isEmpty()) {
                main.row(1).text(0, "No measure was asked.", "normal");
            }
            for (int i = 0; i < p.main.size(); i++) {
                main.row(i + 1);
                Object[] cells = p.main.get(i);
                for (int c = 0; c < cells.length; c++) {
                    Object v = cells[c];
                    if (v instanceof F f) {
                        main.formula(c, f.formula(), f.cached(), "decimal");
                    } else if (v instanceof Double d) {
                        main.number(c, d, "decimal");
                    } else if (v != null) {
                        main.text(c, String.valueOf(v), i == 0 || "".equals(cells[1]) ? "bold" : "normal");
                    }
                }
            }
            for (String[] n : p.names) {
                wb.name(n[0], n[1], n[2]);
            }
        }
    }

    /** {@code ROUNDDOWN(part/whole*10000+0.5,0)/10000}: the showcase's share to four decimals (shares are not negative). */
    private static String share(String part, String whole) {
        return "ROUNDDOWN(" + part + "/" + whole + "*10000+0.5,0)/10000";
    }

    private static void summary(Plan p, Cohort.Summary s, Table t) {
        String samples = p.scalar("samples of the selection", s.samples());
        String resp = p.scalar("responders", s.responders());
        p.row("SUMMARY", "");
        if (!t.isNull("RESPONSE_RATE", 0)) {
            measure(p, "RESPONSE_RATE", share(resp, samples), t.numbers("RESPONSE_RATE")[0]);
        }
        if (s.altered() != null) {
            String alt = p.scalar("samples with the gene altered", s.altered());
            String ra = p.scalar("responders among them", s.respondersAltered());
            String rn = p.scalar("responders among the others", s.respondersNot());
            if (!t.isNull("ALTERED_RATE", 0)) {
                measure(p, "ALTERED_RATE", share(alt, samples), t.numbers("ALTERED_RATE")[0]);
            }
            if (!t.isNull("RESPONSE_IF_ALTERED", 0)) {
                measure(p, "RESPONSE_IF_ALTERED", share(ra, alt), t.numbers("RESPONSE_IF_ALTERED")[0]);
            }
            if (!t.isNull("RESPONSE_IF_NOT", 0)) {
                measure(p, "RESPONSE_IF_NOT", share(rn, "(" + samples + "-" + alt + ")"), t.numbers("RESPONSE_IF_NOT")[0]);
            }
        }
    }

    private static void measure(Plan p, String column, String formula, double cached) {
        int r = p.row(column, new F(formula, cached));
        p.name("OUT_SUMMARY_" + column, 1, r);
    }

    private static void genes(Plan p, Cohort.Matrix m, Table t) {
        String selected = p.scalar("samples of the selection", m.selected());
        p.row("MATRIX GENES", "");
        for (int i = 0; i < m.counts().size(); i++) {
            Cohort.GeneCount g = m.counts().get(i);
            String count = p.scalar("samples with " + g.gene() + " altered", g.samples());
            Object cell = m.selected() == 0 ? (Object) 0.0 : new F(share(count, selected), t.numbers("PCT")[i]);
            int r = p.row(g.gene(), cell);
            if (i == 0) {
                p.name("OUT_MATRIX_GENES_PCT", 1, r);
            }
        }
    }

    private static String quantile(String range, String n, double q) {
        String at = "(" + q + "*(" + n + "-1))";
        String lo = "ROUNDDOWN(" + at + ",0)";
        String hi = "ROUNDUP(" + at + ",0)";
        String a = "SMALL(" + range + "," + lo + "+1)";
        String b = "SMALL(" + range + "," + hi + "+1)";
        return "ROUNDDOWN((" + a + "+(" + b + "-" + a + ")*(" + at + "-" + lo + "))*1000+0.5,0)/1000";
    }

    private static void rounds(Plan p, List<Designs.Round> rounds, Table t) {
        p.row("ROUNDS", "");
        p.row("ROUND", "N", "LOW", "Q1", "MEDIAN", "Q3", "HIGH", "BEST");
        String[] columns = {"LOW", "Q1", "MEDIAN", "Q3", "HIGH"};
        double[] quantiles = {0.05, 0.25, 0.5, 0.75, 0.95};
        for (int k = 0; k < rounds.size(); k++) {
            Designs.Round r = rounds.get(k);
            int col = 3 + k;
            p.data.computeIfAbsent(1, x -> new TreeMap<>()).put(col, "round " + r.round());
            for (int i = 0; i < r.sorted().length; i++) {
                p.data.computeIfAbsent(i + 2, x -> new TreeMap<>()).put(col, r.sorted()[i]);
            }
            String range = "Data!$" + Cells.col(col) + "$2:$" + Cells.col(col) + "$" + (r.sorted().length + 1);
            int row = p.main.size() + 1;
            String n = "$B$" + row;
            Object[] cells = new Object[8];
            cells[0] = String.valueOf(r.round());
            cells[1] = new F("COUNT(" + range + ")", r.sorted().length);
            for (int c = 0; c < 5; c++) {
                cells[2 + c] = new F(quantile(range, n, quantiles[c]), t.numbers(columns[c])[k]);
            }
            cells[7] = new F("ROUNDDOWN(MAX(" + range + ")*1000+0.5,0)/1000", t.numbers("BEST")[k]);
            p.row(cells);
            if (k == 0) {
                for (int c = 0; c < 5; c++) {
                    p.name("OUT_ROUNDS_" + columns[c], 2 + c, row);
                }
                p.name("OUT_ROUNDS_BEST", 7, row);
            }
        }
    }
}
