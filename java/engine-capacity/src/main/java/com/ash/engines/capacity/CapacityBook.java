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
package com.ash.engines.capacity;

import com.ash.tools.xlsx.Sheet;
import com.ash.tools.xlsx.Workbook;
import java.io.OutputStream;
import java.util.Map;

/**
 * The capacity workbook, generated from the planner's own expression trees (nothing is hand-written, so there is no template to drift):
 * {@code Data} holds the request's inputs and the coefficients as values; {@code Main} holds one row a figure, a live formula over defined
 * names wherever the family's spreadsheet formulas can say it, and the value (with its cached result) where the figure joins text. Every
 * input, coefficient and figure is a defined name; {@code OUT_FIGURES_VALUE} is the first number of the answer.
 */
final class CapacityBook {

    private CapacityBook() {
    }

    /** The cell of a figure on Main: one row a figure under a header row, value in column C. */
    static String cellOf(Planner planner, Planner.Figure f) {
        return "$C$" + (f.ordinal() + 1);
    }

    static void write(OutputStream out, Planner planner, Map<String, Object> overrides, Evaluator ev) throws com.ash.engine.EngineException {
        try (Workbook wb = Workbook.to(out)) {
            Sheet data = wb.sheet("Data");
            data.row(1).text(0, "NAME", "header").text(1, "VALUE", "header").text(2, "UNIT", "header");
            int r = 1;
            for (Planner.Input i : planner.inputs()) {
                r++;
                put(data.next(), i.name(), ev.value(i.name()), i.unit());
                wb.name(i.name(), data.name(), "$B$" + r);
            }
            for (String c : planner.coefficientNames()) {
                r++;
                put(data.next(), c, ev.value(c), planner.coefficientUnit(c));
                wb.name(c, data.name(), "$B$" + r);
            }
            Sheet main = wb.sheet("Main");
            main.row(1).text(0, "NAME", "header").text(1, "LABEL", "header").text(2, "VALUE", "header").text(3, "UNIT", "header");
            boolean first = true;
            for (Planner.Figure f : planner.figures()) {
                Object v = ev.value(f.name());
                main.next().text(0, f.name(), "normal").text(1, f.label(), "normal");
                if (Expr.needsText(f.node()) || v instanceof Boolean) {
                    put(main, 2, f.name(), v);                      // a value: the family's formulas have no text join, CHAR, INT or ROUND
                } else if (v instanceof Double d) {
                    main.formula(2, Expr.toExcel(f.node()), d, "decimal");
                } else {
                    main.formulaText(2, Expr.toExcel(f.node()), Evaluator.text(v), "normal");
                }
                main.text(3, f.unit(), "normal");
                wb.name(f.name(), main.name(), cellOf(planner, f));
                if (first && v instanceof Double) {
                    wb.name("OUT_FIGURES_VALUE", main.name(), cellOf(planner, f));
                    first = false;
                }
            }
        }
    }

    private static void put(Sheet row, String name, Object v, String unit) {
        row.text(0, name, "normal");
        put(row, 1, name, v);
        row.text(2, unit, "normal");
    }

    private static void put(Sheet row, int col, String name, Object v) {
        if (v instanceof Double d) {
            row.number(col, d, "decimal");
        } else {
            row.text(col, Evaluator.text(v), "normal");
        }
    }
}
