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

import com.ash.engine.Answer;
import com.ash.engine.Answers;
import com.ash.engine.Cell;
import com.ash.engine.Changes;
import com.ash.engine.Control;
import com.ash.engine.Descriptor;
import com.ash.engine.EngineContext;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.Explaining;
import com.ash.engine.Explanation;
import com.ash.engine.Incremental;
import com.ash.engine.InputTable;
import com.ash.engine.Inputs;
import com.ash.engine.MemoryBudget;
import com.ash.engine.Missing;
import com.ash.engine.Request;
import com.ash.engine.Session;
import com.ash.engine.Snapshot;
import com.ash.engine.Step;
import com.ash.engine.Steps;
import com.ash.engine.Table;
import com.ash.engine.text.Canonical;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A python session: the approved code of one snapshot, replaced whole by {@link #apply} when an approval changes. It holds no viewer's
 * rows: every ask reads its frames through {@link Inputs#table(String, String)} as the caller, hands them to a worker as files that live
 * only as long as the request, and keeps nothing of them. One approved item is run per output asked.
 */
final class PythonSession implements Session, Explaining, Incremental {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final int MAX_JSON_ANSWER = 64 << 20;

    private final EngineContext context;
    private final Descriptor descriptor;
    private final PythonPool pool;
    private final Snapshot snapshot;
    private final Inputs inputs;
    private final List<String> lacking;
    private final MemoryBudget.Reservation hold;
    private volatile ApprovedCode code;
    private volatile boolean closed;

    PythonSession(EngineContext context, Descriptor descriptor, PythonPool pool, Snapshot snapshot, Inputs inputs, ApprovedCode code,
            List<String> lacking, MemoryBudget.Reservation hold) {
        this.context = context;
        this.descriptor = descriptor;
        this.pool = pool;
        this.snapshot = snapshot;
        this.inputs = inputs;
        this.code = code;
        this.lacking = List.copyOf(lacking);
        this.hold = hold;
    }

    @Override
    public Snapshot snapshot() {
        return snapshot;
    }

    @Override
    public long memoryBytes() {
        ApprovedCode c = code;
        return closed || c == null ? 0 : 65_536 + c.bytes();
    }

    // ------------------------------------------------------------------------------------------------------------------ ask

    /** What one output's run gave: its table and the words about it, or the inputs it could not find. */
    private record Run(Table table, List<String> notes, List<Missing> missing) {}

    @Override
    public Answer ask(Request request, Control control) throws EngineException {
        ApprovedCode now = code;
        if (now == null) {
            return Answers.notFound(context, this, request, List.of(new Missing("code", "platform/code-item@1", snapshot, lacking, request.outputs())));
        }
        List<Table> tables = new ArrayList<>();
        List<Missing> missing = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (String output : request.outputs()) {
            control.check();
            Run run = run(now, request, output, control);
            if (!run.missing().isEmpty()) {
                missing.addAll(run.missing());
                continue;
            }
            tables.add(run.table());
            notes.addAll(run.notes());
        }
        Answer a = Answers.of(context, this, request, tables, missing);
        return notes.isEmpty() ? a : Answers.withNotes(a, notes);
    }

    private Run run(ApprovedCode now, Request request, String output, Control control) throws EngineException {
        Descriptor.Output spec = descriptor.output(output);
        if (spec == null) {
            throw new EngineException(Codes.REQUEST, Kind.REFUSED, "the python engine has no output " + output);
        }
        CodeItem item = now.find(text(request, "item"), text(request, "version"));
        if (!"check".equals(output) && !output.equals(item.iface())) {
            throw new EngineException(Codes.REQUEST, Kind.REFUSED, item.id() + " is a " + item.iface() + " item: ask the output " + item.iface()
                    + ", not " + output);
        }
        PoolSettings s = pool.settings();
        long id = SEQ.incrementAndGet();
        try (Scratch scratch = Scratch.create(pool.scratchRoot(), request.id() + "." + output + "." + id)) {
            List<Missing> missing = new ArrayList<>();
            Map<String, String> files = "check".equals(output) ? Map.of() : hand(request, output, scratch, missing, control);
            if (!missing.isEmpty()) {
                return new Run(null, List.of(), missing);
            }
            Map<String, Object> params = params(request, output, item, s);
            PoolRequest pr = new PoolRequest(scratch.dir().getFileName().toString(), "engine_python.engine:PythonEngine", output, params, request.actor(),
                    List.of(), request.visibility() == null ? "" : request.visibility().signature(), request.requestClass(), request.seed(), scratch, files,
                    control.deadline(), control::cancelled, p -> progress(control, p), s.cpuSeconds());
            PoolResult result;
            try {
                result = pool.submit(pr);
            } catch (PoolException e) {
                throw e.toEngine();
            }
            if (!result.rows().isEmpty() && result.rows().get(0).get("error") instanceof Map<?, ?> err) {
                throw failure(item, err);
            }
            Table table;
            try {
                table = Outputs.table(descriptor, output, result.rows());
            } catch (IllegalArgumentException e) {
                throw new EngineException(Codes.PROTOCOL, Kind.FAILED, "the worker's rows do not fit the output " + output + ": " + e.getMessage());
            }
            List<String> notes = new ArrayList<>();
            notes.add("code " + item.id() + " sha384:" + item.hash());
            if ("transform".equals(output) && result.meta().get("columns") != null) {
                notes.add("columns " + Canonical.json(result.meta().get("columns")));
            }
            if (Boolean.TRUE.equals(result.meta().get("truncated"))) {
                notes.add("the result is cut at the first rows: pyarrow is not installed in the worker's environment");
            }
            return new Run(table, notes, List.of());
        }
    }

    /** The author's failure as one typed refusal: the message is the author's own words, the traceback is in the detail. */
    private static EngineException failure(CodeItem item, Map<?, ?> err) {
        String kind = String.valueOf(err.get("kind"));
        String words = item.id() + ": " + err.get("error");
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("item", item.id());
        detail.put("traceback", String.valueOf(err.get("traceback")));
        return switch (kind) {
            case "sandbox" -> new EngineException(Codes.SANDBOX, Kind.NOT_PERMITTED, words, detail, null);
            case "limit" -> new EngineException(Codes.LIMIT, Kind.LIMIT, words, detail, null);
            case "invalid" -> new EngineException(Codes.INVALID, Kind.REFUSED, words, detail, null);
            default -> new EngineException(Codes.FAILED, Kind.FAILED, words, detail, null);
        };
    }

    private static void progress(Control control, Map<String, Object> p) {
        Object done = p.get("done");
        Object of = p.get("of");
        if (done instanceof Number d && of instanceof Number o && o.doubleValue() > 0) {
            control.progress(Math.min(1.0, d.doubleValue() / o.doubleValue()), "python " + d.longValue() + " of " + o.longValue());
        }
    }

    // -------------------------------------------------------------------------------------------------------- hand-over

    /** Reads the tables the request names as the caller and writes them as Arrow files; the table's absence is a missing input, not a failure. */
    @SuppressWarnings("unchecked")
    private Map<String, String> hand(Request request, String output, Scratch scratch, List<Missing> missing, Control control) throws EngineException {
        Object spec = request.param("tables");
        Map<String, String> files = new LinkedHashMap<>();
        if (!(spec instanceof List<?> list) || list.isEmpty()) {
            throw new EngineException(Codes.REQUEST, Kind.REFUSED, "the output " + output + " reads frames: name them in the parameter tables");
        }
        PoolSettings s = pool.settings();
        for (Object o : list) {
            Map<String, Object> t = (Map<String, Object>) o;
            String name = String.valueOf(t.get("name"));
            String as = t.get("as") == null ? name : String.valueOf(t.get("as"));
            List<FrameFiles.Col> cols = new ArrayList<>();
            if (t.get("columns") instanceof List<?> l) {
                for (Object c : l) {
                    try {
                        cols.add(FrameFiles.Col.parse(String.valueOf(c)));
                    } catch (IllegalArgumentException e) {
                        throw new EngineException(Codes.REQUEST, Kind.REFUSED, e.getMessage());
                    }
                }
            }
            if (cols.isEmpty()) {
                throw new EngineException(Codes.REQUEST, Kind.REFUSED, "the table " + name + " lists no columns (NAME:type): the code is handed only those");
            }
            InputTable table = inputs.table("frames", name);
            if (!table.present()) {
                missing.add(new Missing("frames", "", snapshot, List.of(), List.of(output)));
                continue;
            }
            List<String> lack = cols.stream().map(FrameFiles.Col::name).filter(c -> !table.columns().contains(c)).toList();
            if (!lack.isEmpty()) {
                missing.add(new Missing("frames", "", snapshot, lack, List.of(output)));
                continue;
            }
            try {
                FrameFiles.write(table, cols, scratch.input(as), s.maxInputRows(), control, 0);
            } catch (IllegalArgumentException e) {
                throw new EngineException(Codes.REQUEST, Kind.REFUSED, e.getMessage());
            }
            files.put(as, Scratch.relative(as));
        }
        return files;
    }

    /** The worker's parameters for one output: the code, and the request's parameters under the names the worker reads. */
    private Map<String, Object> params(Request request, String output, CodeItem item, PoolSettings s) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("code", item.wire());
        copy(request, p, "inputs", "inputs");
        copy(request, p, "group-by", "group_by");
        copy(request, p, "frame", "frame");
        copy(request, p, "frames", "frames");
        copy(request, p, "names", "names");
        copy(request, p, "extracts", "extracts");
        copy(request, p, "contract", "contract");
        copy(request, p, "params", "params");
        copy(request, p, "format", "format");
        copy(request, p, "shards", "shards");
        copy(request, p, "batch-rows", "batch-rows");
        p.put("allowed", s.packages());
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("max_rows", s.maxRows());
        limits.put("max_groups", s.maxGroups());
        limits.put("max_output_bytes", Math.min(s.maxResultBytes(), MAX_JSON_ANSWER));
        limits.put("memory", s.memoryBytes() > 0 ? Math.max(1, (int) (s.memoryBytes() >> 20)) : 0);
        if (item.timeoutSeconds() > 0) {
            limits.put("timeout", item.timeoutSeconds());
        }
        if (item.memoryMb() > 0) {
            limits.put("memory", s.memoryBytes() > 0 ? Math.min(item.memoryMb(), (int) (s.memoryBytes() >> 20)) : item.memoryMb());
        }
        p.put("limits", limits);
        return p;
    }

    private static void copy(Request r, Map<String, Object> to, String from, String as) {
        Object v = r.param(from);
        if (v != null && !(v instanceof List<?> l && l.isEmpty()) && !(v instanceof Map<?, ?> m && m.isEmpty())) {
            to.put(as, v);
        }
    }

    private static String text(Request r, String name) throws EngineException {
        Object v = r.param(name);
        if (v == null || v.toString().isBlank()) {
            if ("version".equals(name)) {
                return null;
            }
            throw new EngineException(Codes.REQUEST, Kind.REFUSED, "the parameter " + name + " is needed");
        }
        return v.toString();
    }

    // -------------------------------------------------------------------------------------------------------- explain and workbook

    @Override
    public Explanation explain(Request request, Cell cell, Control control) throws EngineException {
        Descriptor.Output spec = descriptor.output(cell.output());
        if (spec == null || spec.measures().stream().noneMatch(m -> m.name().equals(cell.column()))) {
            throw new EngineException("ENG-3005", Kind.REFUSED, "only a measure column of the python engine is explained: " + cell.output() + "." + cell.column());
        }
        Answer a = ask(only(request, request.outputs().contains(cell.output()) ? request.outputs() : List.of(cell.output())), control);
        Table t = a.table(cell.output());
        int row = t == null ? -1 : find(t, cell);
        if (row < 0 || t.isNull(cell.column(), row)) {
            throw new EngineException("ENG-3005", Kind.REFUSED, "the cell " + cell.key() + " is not in this request's answer");
        }
        ApprovedCode now = code;
        if (now == null) {
            throw new EngineException("ENG-5020", Kind.REFUSED, "the session is closed");
        }
        CodeItem item = now.find(text(request, "item"), text(request, "version"));
        int header = Book.layout(a.tables()).get(cell.output());
        double value = t.numbers(cell.column())[row];
        Step step = Steps.opaque("code", item.id() + " returned this value",
                "approved Python code (" + item.iface() + ", hash " + item.hash().substring(0, 12) + ") is not expressible in Rachana-EL; the workbook holds the value it returned",
                Book.cell(t, header, cell.column(), row), value);
        return new Explanation(cell, value, List.of(step));
    }

    private static int find(Table t, Cell cell) {
        for (int r = 0; r < t.rows(); r++) {
            boolean all = true;
            for (String k : t.schema().key()) {
                String want = cell.key().get(k);
                Object have = t.value(k, r);
                if (want != null && !want.equals(String.valueOf(have))) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return r;
            }
        }
        return -1;
    }

    private static Request only(Request r, List<String> outputs) {
        return new Request(r.id(), outputs, new java.util.TreeMap<>(r.params()), r.visibility(), r.actor(), r.seed(), r.requestClass());
    }

    @Override
    public void workbook(Request request, OutputStream out, Control control) throws EngineException, IOException {
        Answer a = ask(request, control);
        if (a.tables().isEmpty()) {
            throw new EngineException("ENG-3005", Kind.REFUSED, "the request has no answer to put in a workbook");
        }
        ApprovedCode now = code;
        if (now == null) {
            throw new EngineException("ENG-5020", Kind.REFUSED, "the session is closed");
        }
        Book.write(out, now.find(text(request, "item"), text(request, "version")), a.tables());
    }

    // -------------------------------------------------------------------------------------------------------- changes

    @Override
    public boolean apply(List<Changes> changes, Control control) throws EngineException {
        ApprovedCode now = code;
        if (now == null || changes.stream().anyMatch(c -> !"code".equals(c.input()))) {
            return false;
        }
        control.check();
        ApprovedCode next = now.with(changes);
        if (hold != null) {
            hold.resize(65_536 + next.bytes());
        }
        code = next;
        return true;
    }

    @Override
    public void close() {
        closed = true;
        code = null;
        if (hold != null) {
            hold.close();
        }
    }

    /** True once the hand-over folder of a finished request is gone (for tests). */
    static boolean gone(java.nio.file.Path dir) {
        return !Files.exists(dir);
    }
}
