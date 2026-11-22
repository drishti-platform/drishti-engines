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
package com.ash.engines.query.out;

import com.ash.engine.Control;
import com.ash.engine.EngineException;
import com.ash.engine.Filter;
import com.ash.engine.InputTable;
import com.ash.engines.query.exec.Executor;
import com.ash.engines.query.exec.Keys;
import com.ash.engines.query.expr.Ast;
import com.ash.engines.query.plan.Agg;
import com.ash.engines.query.plan.Plan;
import com.ash.tools.xlsx.Sheet;
import com.ash.tools.xlsx.Workbook;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * The formula workbook of an answer: {@code Data} holds the table's rows the plan reads (values only, a column {@code _ROW} of ones
 * for counts), {@code Main} one row per cell with its figure as a formula over {@code Data} ({@code SUMIFS}, {@code COUNTIFS},
 * {@code MINIFS}, {@code MAXIFS}, {@code AVERAGEIFS} on the cell's coordinates, and the value's formula in Excel's words), and the
 * defined name {@code OUT_CELLS_NUMBER} on the first cell.
 *
 * <p>Draft 1 translates values made of {@code sum}, {@code count}, {@code min}, {@code max}, {@code avg} of a field without their own
 * condition, arithmetic and {@code abs}, {@code sqrt}, {@code power}, {@code round}, {@code ln}, {@code exp}, over cells whose
 * dimensions are plain columns; any other cell is written as its value (listed in QUERY_ENGINE.md section 11 as work).
 */
public final class QueryWorkbook {

    private final Plan plan;
    private final Executor executor;
    private final Answering.Built built;
    private final long maxRows;

    public QueryWorkbook(Plan plan, Executor executor, Answering.Built built, long maxRows) {
        this.plan = plan;
        this.executor = executor;
        this.built = built;
        this.maxRows = maxRows;
    }

    public void write(OutputStream out, InputTable table, Filter visibility, Control control) throws EngineException {
        List<String> fields = new ArrayList<>(plan.fields());
        fields.removeIf(f -> !table.columns().contains(f));
        List<Map<String, Object>> rows = executor.rows(table, visibility, Map.of(), null, java.util.Set.of(), null, fields, maxRows, control);
        int last = Math.max(2, rows.size() + 1);
        try (Workbook wb = Workbook.to(out)) {
            Sheet data = wb.sheet("Data");
            data.row(1);
            for (int i = 0; i < fields.size(); i++) {
                data.text(i, fields.get(i), "header");
            }
            data.text(fields.size(), "_ROW", "header");
            for (Map<String, Object> r : rows) {
                data.next();
                for (int i = 0; i < fields.size(); i++) {
                    data.value(i, r.get(fields.get(i)));
                }
                data.number(fields.size(), 1L, "int");
            }
            Sheet main = wb.sheet("Main");
            main.row(1).text(0, "ROW", "header").text(1, "COLUMN", "header").text(2, "VALUE", "header").text(3, "NUMBER", "header");
            int r = 1;
            for (Answering.Node rn : built.rows()) {
                for (Answering.Node cn : built.columns()) {
                    for (Plan.Value v : plan.values()) {
                        Answering.Figure f = built.cell(rn.key(), cn.key(), v.label());
                        if (f == null) {
                            continue;
                        }
                        main.row(++r).text(0, rn.key(), "normal").text(1, cn.key(), "normal").text(2, v.label(), "normal");
                        String excel = v.showAs() == null ? excel(v.formula(), rn, cn, fields, last) : null;
                        if (excel != null && f.number() != null) {
                            main.formula(3, excel, f.number(), "decimal");
                        } else if (f.number() != null) {
                            main.number(3, f.number(), "decimal");
                        } else if (f.text() != null) {
                            main.text(3, f.text(), "normal");
                        }
                    }
                }
            }
            if (r >= 2) {
                wb.name("OUT_CELLS_NUMBER", main.name(), "$D$2");
            }
        }
    }

    /** A value's formula in Excel's words over Data, or null when it does not translate. */
    private String excel(Ast f, Answering.Node r, Answering.Node c, List<String> fields, int last) {
        if (r.others() || c.others()) {
            return null;
        }
        SortedMap<String, String> coords = Keys.read(r.key());
        coords.putAll(Keys.read(c.key()));
        StringBuilder crit = new StringBuilder();
        for (Map.Entry<String, String> e : coords.entrySet()) {
            Plan.Dim d = plan.dim(e.getKey());
            if (d == null || !d.column() || !fields.contains(d.field())) {
                return null;
            }
            String m = e.getValue();
            crit.append(", ").append(range(fields.indexOf(d.field()), last)).append(", ")
                    .append(Plan.BLANK.equals(m) ? "\"=\"" : "\"" + m.replace("\"", "\"\"") + "\"");
        }
        return translate(f, crit.toString(), fields, last);
    }

    private String translate(Ast f, String crit, List<String> fields, int last) {
        return switch (f) {
            case Ast.Num n -> n.el();
            case Ast.Unary u when "-".equals(u.op()) -> {
                String x = translate(u.operand(), crit, fields, last);
                yield x == null ? null : "-(" + x + ")";
            }
            case Ast.Binary b when List.of("+", "-", "*", "/").contains(b.op()) -> {
                String l = translate(b.left(), crit, fields, last);
                String rr = translate(b.right(), crit, fields, last);
                yield l == null || rr == null ? null : "(" + l + b.op() + rr + ")";
            }
            case Ast.Call call when Agg.Fn.named(call.name()) != null -> aggregate(Agg.of(call, null), crit, fields, last);
            case Ast.Call call when List.of("abs", "sqrt", "power", "round", "ln", "exp").contains(call.name()) -> {
                List<String> args = new ArrayList<>();
                for (Ast a : call.args()) {
                    String x = translate(a, crit, fields, last);
                    if (x == null) {
                        yield null;
                    }
                    args.add(x);
                }
                yield call.name().toUpperCase(java.util.Locale.ROOT) + "(" + String.join(",", args) + ")";
            }
            default -> null;
        };
    }

    private String aggregate(Agg a, String crit, List<String> fields, int last) {
        if (a.where() != null) {
            return null;
        }
        String ones = range(fields.size(), last);
        if (a.fn() == Agg.Fn.COUNT && a.arg() == null) {
            return crit.isEmpty() ? "SUM(" + ones + ")" : "SUMIFS(" + ones + crit + ")";
        }
        if (!(a.arg() instanceof Ast.Ref ref) || !fields.contains(ref.name())) {
            return null;
        }
        String x = range(fields.indexOf(ref.name()), last);
        return switch (a.fn()) {
            case SUM -> crit.isEmpty() ? "SUM(" + x + ")" : "SUMIFS(" + x + crit + ")";
            case COUNT -> "COUNTIFS(" + x + ", \"<>\"" + crit + ")";
            case MIN -> crit.isEmpty() ? "MIN(" + x + ")" : "MINIFS(" + x + crit + ")";
            case MAX -> crit.isEmpty() ? "MAX(" + x + ")" : "MAXIFS(" + x + crit + ")";
            case AVG -> crit.isEmpty() ? "AVERAGE(" + x + ")" : "AVERAGEIFS(" + x + crit + ")";
            default -> null;
        };
    }

    private static String range(int col, int last) {
        String c = column(col);
        return "Data!$" + c + "$2:$" + c + "$" + last;
    }

    private static String column(int i) {
        StringBuilder b = new StringBuilder();
        int n = i + 1;
        while (n > 0) {
            int rem = (n - 1) % 26;
            b.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return b.toString();
    }
}
