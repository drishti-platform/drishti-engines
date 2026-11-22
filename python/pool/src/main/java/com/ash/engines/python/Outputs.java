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
import com.ash.engine.Descriptor;
import com.ash.engine.Table;
import com.ash.engine.TableSchema;
import com.ash.engine.Tables;
import java.util.List;
import java.util.Map;

/** Rows of JSON values (what a worker answers) to the engine's tables, by the descriptor's own schemas: one place, so no output is shaped twice. */
final class Outputs {

    private Outputs() {
    }

    /** The table of {@code output} holding {@code rows}; IllegalArgumentException (in words) when a row does not fit the schema. */
    static Table table(Descriptor d, String output, List<Map<String, Object>> rows) {
        TableSchema schema = d.schema(output);
        Tables.Builder b = Tables.of(schema);
        for (Map<String, Object> row : rows) {
            Object[] values = new Object[schema.columns().size()];
            for (int c = 0; c < values.length; c++) {
                ColumnSchema col = schema.columns().get(c);
                values[c] = value(output, col, row.get(col.name()));
            }
            b.row(values);
        }
        return b.build();
    }

    private static Object value(String output, ColumnSchema c, Object v) {
        if (v == null) {
            return null;
        }
        try {
            return switch (c.carrier()) {
                case TEXT -> v instanceof String s ? s : String.valueOf(v);
                case INTEGER -> ((Number) v).longValue();
                case NUMBER -> ((Number) v).doubleValue();
                case FLAG -> (Boolean) v;
                case LIST -> throw new IllegalArgumentException("a list column");
            };
        } catch (ClassCastException | IllegalArgumentException e) {
            throw new IllegalArgumentException(output + "." + c.name() + " is " + c.type() + "; the worker gave " + v.getClass().getSimpleName() + " " + v);
        }
    }
}
