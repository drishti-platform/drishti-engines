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

import com.ash.engine.Batch;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reading a row's values the way the showcase always has: a lake gives numbers as whole numbers, fractions or text, so every value is
 * read as text, as a number or as a whole number with a fallback (never an exception), and a batch of columns becomes rows of values.
 */
final class V {

    /** One row: column to value (String, Long, Double, Boolean or null). */
    interface Row extends Map<String, Object> {
    }

    private static final class RowMap extends LinkedHashMap<String, Object> implements Row {
        private static final long serialVersionUID = 1L;
    }

    private V() {
    }

    /** The rows of a batch, in its order, with the columns named. */
    static List<Row> rows(Batch b, List<String> columns) {
        Object[][] cols = new Object[columns.size()][];
        for (int c = 0; c < columns.size(); c++) {
            cols[c] = column(b, columns.get(c));
        }
        List<Row> out = new ArrayList<>(b.rows());
        for (int i = 0; i < b.rows(); i++) {
            RowMap r = new RowMap();
            for (int c = 0; c < cols.length; c++) {
                r.put(columns.get(c), cols[c][i]);
            }
            out.add(r);
        }
        return out;
    }

    /** One column as objects: the batch's accessor for its type is found by trying the four in turn. */
    private static Object[] column(Batch b, String name) {
        int n = b.rows();
        Object[] out = new Object[n];
        try {
            String[] t = b.texts(name);
            for (int i = 0; i < n; i++) {
                out[i] = b.isNull(name, i) ? null : t[i];
            }
            return out;
        } catch (IllegalArgumentException notText) {
            // another carrier
        }
        try {
            long[] t = b.integers(name);
            for (int i = 0; i < n; i++) {
                out[i] = b.isNull(name, i) ? null : (Object) t[i];
            }
            return out;
        } catch (IllegalArgumentException notInteger) {
            // another carrier
        }
        try {
            double[] t = b.numbers(name);
            for (int i = 0; i < n; i++) {
                out[i] = b.isNull(name, i) ? null : (Object) t[i];
            }
            return out;
        } catch (IllegalArgumentException notNumber) {
            // another carrier
        }
        boolean[] t = b.flags(name);
        for (int i = 0; i < n; i++) {
            out[i] = b.isNull(name, i) ? null : (Object) t[i];
        }
        return out;
    }

    /** A value as text ("" for none). */
    static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** A value as a number, or NaN. */
    static double num(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v == null) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** A value as a whole number, or the fallback. */
    static int integer(Object v, int fallback) {
        double d = num(v);
        return Double.isNaN(d) ? fallback : (int) Math.round(d);
    }

    /** A number rounded to the decimals given (for answers people read), as the showcase rounds. */
    static double round(double v, int decimals) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return v;
        }
        double f = StrictMath.pow(10, decimals);
        return Math.round(v * f) / f;
    }

    /** A sample responded: RESPONSE is a yes word (responder, response, CR, PR, yes, true, 1). */
    static boolean responded(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        String s = str(v).trim().toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
        return s.equals("responder") || s.equals("response") || s.equals("yes") || s.equals("y") || s.equals("true") || s.equals("1") || s.equals("cr")
                || s.equals("pr") || s.equals("complete response") || s.equals("partial response") || s.equals("r");
    }
}
