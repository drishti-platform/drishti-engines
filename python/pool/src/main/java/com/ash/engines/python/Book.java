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

import com.ash.engine.ColumnSchema;
import com.ash.engine.Table;
import com.ash.tools.xlsx.Sheet;
import com.ash.tools.xlsx.Workbook;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The workbook of an answer and the cell references an explanation uses, from one layout so they agree. {@code Data} holds values only:
 * the code's identity, then each answered output as a block (a header row, then its rows). {@code Main} holds formulas over {@code Data},
 * one per measure cell of the block, and a defined name {@code OUT_<OUTPUT>_<COLUMN>} on the first row's figure. The python engine's
 * figures are produced by approved code, so their one step is opaque: the workbook shows the value the code returned, and names the cell.
 */
final class Book {

    private static final int IDENTITY_ROW = 2;
    private static final int FIRST_BLOCK = 5;

    private Book() {
    }

    /** Where each output's header row is on {@code Data} (rows after it are the output's rows). */
    static Map<String, Integer> layout(List<Table> tables) {
        Map<String, Integer> at = new LinkedHashMap<>();
        int row = FIRST_BLOCK;
        for (Table t : tables) {
            at.put(t.schema().name(), row);
            row += t.rows() + 3;
        }
        return at;
    }

    /** The A1 column letter of a 0-based column index. */
    static String letter(int index) {
        StringBuilder b = new StringBuilder();
        for (int i = index; i >= 0; i = i / 26 - 1) {
            b.insert(0, (char) ('A' + i % 26));
        }
        return b.toString();
    }

    /** The {@code Data} cell holding row {@code row} (0-based) of {@code column} of the output whose header is at {@code header}. */
    static String cell(Table t, int header, String column, int row) {
        int c = t.schema().index(column);
        return "Data!$" + letter(c) + "$" + (header + 1 + row);
    }

    static void write(OutputStream out, CodeItem item, List<Table> tables) {
        Map<String, Integer> at = layout(tables);
        try (Workbook wb = Workbook.to(out)) {
            Sheet data = wb.sheet("Data");
            data.row(1).text(0, "ITEM", "header").text(1, "VERSION", "header").text(2, "HASH", "header").text(3, "INTERFACE", "header");
            data.row(IDENTITY_ROW).text(0, item.name(), "normal").text(1, item.version(), "normal").text(2, item.hash(), "normal")
                    .text(3, item.iface(), "normal");
            for (Table t : tables) {
                int header = at.get(t.schema().name());
                var h = data.row(header);
                List<ColumnSchema> cols = t.schema().columns();
                for (int c = 0; c < cols.size(); c++) {
                    h.text(c, cols.get(c).name(), "header");
                }
                for (int r = 0; r < t.rows(); r++) {
                    var row = data.row(header + 1 + r);
                    for (int c = 0; c < cols.size(); c++) {
                        put(row, c, cols.get(c), t, r);
                    }
                }
            }
            Sheet main = wb.sheet("Main");
            main.row(1).text(0, "The figures below are what the approved code " + item.id() + " returned (hash " + shortHash(item.hash())
                    + "); they are read from Data, not recomputed: Python code has no spreadsheet formula.", "bold");
            int line = 2;
            for (Table t : tables) {
                int header = at.get(t.schema().name());
                for (ColumnSchema c : t.schema().columns()) {
                    if (!c.measure()) {
                        continue;
                    }
                    for (int r = 0; r < t.rows(); r++) {
                        line++;
                        Double v = t.isNull(c.name(), r) ? null : t.numbers(c.name())[r];
                        main.row(line).text(0, t.schema().name() + " " + c.name() + " " + key(t, r), "normal")
                                .formula(1, cell(t, header, c.name(), r), v, "decimal");
                        if (r == 0) {
                            wb.name("OUT_" + t.schema().name().toUpperCase(Locale.ROOT).replace('-', '_') + "_" + c.name().toUpperCase(Locale.ROOT)
                                    .replace('-', '_'), main.name(), "$B$" + line);
                        }
                    }
                }
            }
        }
    }

    private static String key(Table t, int row) {
        StringBuilder b = new StringBuilder();
        for (String k : t.schema().key()) {
            if (!k.equals("ITEM")) {
                b.append(b.length() == 0 ? "" : " ").append(t.value(k, row));
            }
        }
        return b.toString();
    }

    private static String shortHash(String h) {
        return h.length() > 12 ? h.substring(0, 12) : h;
    }

    private static void put(Sheet row, int c, ColumnSchema col, Table t, int r) {
        if (t.isNull(col.name(), r)) {
            return;
        }
        switch (col.carrier()) {
            case TEXT -> row.text(c, t.texts(col.name())[r], "normal");
            case INTEGER -> row.number(c, t.integers(col.name())[r], "int");
            case NUMBER -> row.number(c, t.numbers(col.name())[r], "decimal");
            case FLAG -> row.text(c, String.valueOf(t.flags(col.name())[r]), "normal");
            case LIST -> row.text(c, "list", "normal");
        }
    }
}
