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

import com.ash.engine.Batch;
import com.ash.engine.ColumnSchema;
import com.ash.engine.Control;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.Filter;
import com.ash.engine.InputTable;
import com.ash.engine.Scan;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowStreamWriter;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * The column hand-over (EN-48): an input table's batches become one Arrow IPC stream file in the request's scratch folder, which the
 * worker reads (its own codec, no copy of the numbers). Arrow is used only between the processes; the engine's own API is column
 * arrays by reference. The host has already applied the viewer's row rules and masks (an open input is read as the caller), so what is
 * written here is exactly what the person may see, and the file lives only as long as the request.
 */
final class FrameFiles {

    /** One column to hand over: its name and Data Protocol type ({@code string}, {@code integer}, {@code number}, {@code boolean}, {@code decimal}, {@code date}, ...). */
    record Col(String name, String type) {

        /** {@code NAME:type} as the parameter {@code frame-types} lists it. */
        static Col parse(String text) {
            int i = text.lastIndexOf(':');
            if (i <= 0 || i == text.length() - 1) {
                throw new IllegalArgumentException("a frame column is written NAME:type, not '" + text + "'");
            }
            return new Col(text.substring(0, i).strip(), text.substring(i + 1).strip());
        }
    }

    private static final int BATCH_ROWS = 65_536;

    private FrameFiles() {
    }

    /** What was written. */
    record Written(long rows, long bytes) {}

    /**
     * Writes {@code table}'s {@code columns} to {@code file}. Refuses (PYT-2010) when the table has more than {@code maxRows} rows, before
     * reading any when the host knows the count, else when the count passes it while reading. Calls {@code control.check()} per batch.
     */
    static Written write(InputTable table, List<Col> columns, Path file, int maxRows, Control control, long memoryLimit) throws EngineException {
        if (table.rows() > maxRows) {
            throw tooMany(table.name(), table.rows(), maxRows);
        }
        List<Field> fields = new ArrayList<>();
        for (Col c : columns) {
            fields.add(new Field(c.name(), FieldType.nullable(arrowType(c)), null));
        }
        long[] rows = {0};
        try (BufferAllocator alloc = new RootAllocator(memoryLimit > 0 ? memoryLimit : Long.MAX_VALUE);
                VectorSchemaRoot root = VectorSchemaRoot.create(new Schema(fields), alloc);
                OutputStream out = Files.newOutputStream(file);
                ArrowStreamWriter w = new ArrowStreamWriter(root, null, Channels.newChannel(out))) {
            w.start();
            table.scan(new Scan(columns.stream().map(Col::name).toList(), Filter.ALL, BATCH_ROWS), batch -> {
                control.check();
                rows[0] += batch.rows();
                if (rows[0] > maxRows) {
                    throw tooMany(table.name(), rows[0], maxRows);
                }
                root.allocateNew();
                for (int c = 0; c < columns.size(); c++) {
                    fill(root.getVector(c), columns.get(c), batch);
                }
                root.setRowCount(batch.rows());
                try {
                    w.writeBatch();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            w.end();
        } catch (UncheckedIOException e) {
            throw new EngineException("ENG-9000", Kind.FAILED, "a frame could not be written for the worker: " + e.getCause().getMessage());
        } catch (IOException e) {
            throw new EngineException("ENG-9000", Kind.FAILED, "a frame could not be written for the worker: " + e.getMessage());
        } catch (OutOfMemoryError e) {
            throw new EngineException(Codes.FRAME_LIMIT, Kind.LIMIT, "the frame " + table.name() + " does not fit in the memory the hand-over may use");
        }
        try {
            return new Written(rows[0], Files.size(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static EngineException tooMany(String name, long rows, int max) {
        return new EngineException(Codes.FRAME_LIMIT, Kind.LIMIT, "the frame " + name + " has " + rows + " rows; the python engine takes at most " + max
                + " (option max-input-rows): group or filter it first");
    }

    private static ArrowType arrowType(Col c) {
        return switch (ColumnSchema.Carrier.of(c.type())) {
            case TEXT -> ArrowType.Utf8.INSTANCE;
            case INTEGER -> new ArrowType.Int(64, true);
            case NUMBER -> new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
            case FLAG -> ArrowType.Bool.INSTANCE;
            case LIST -> throw new IllegalArgumentException("the column " + c.name() + " is a list: list columns are not handed to Python code");
        };
    }

    private static void fill(FieldVector v, Col c, Batch b) {
        int n = b.rows();
        switch (ColumnSchema.Carrier.of(c.type())) {
            case TEXT -> {
                String[] a = b.texts(c.name());
                VarCharVector x = (VarCharVector) v;
                for (int i = 0; i < n; i++) {
                    if (a[i] == null || b.isNull(c.name(), i)) {
                        x.setNull(i);
                    } else {
                        x.setSafe(i, a[i].getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
            case INTEGER -> {
                long[] a = b.integers(c.name());
                BigIntVector x = (BigIntVector) v;
                for (int i = 0; i < n; i++) {
                    if (b.isNull(c.name(), i)) {
                        x.setNull(i);
                    } else {
                        x.setSafe(i, a[i]);
                    }
                }
            }
            case NUMBER -> {
                double[] a = b.numbers(c.name());
                Float8Vector x = (Float8Vector) v;
                for (int i = 0; i < n; i++) {
                    if (b.isNull(c.name(), i)) {
                        x.setNull(i);
                    } else {
                        x.setSafe(i, a[i]);
                    }
                }
            }
            case FLAG -> {
                boolean[] a = b.flags(c.name());
                BitVector x = (BitVector) v;
                for (int i = 0; i < n; i++) {
                    if (b.isNull(c.name(), i)) {
                        x.setNull(i);
                    } else {
                        x.setSafe(i, a[i] ? 1 : 0);
                    }
                }
            }
            case LIST -> throw new IllegalArgumentException("list columns are not handed to Python code");
        }
        v.setValueCount(n);
    }
}
