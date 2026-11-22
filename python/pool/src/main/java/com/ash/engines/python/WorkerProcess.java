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

import com.ash.engine.text.Canonical;
import com.ash.engines.python.PoolException.Code;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One worker process and its JSON-lines control channel (the worker protocol). It runs one request at a time:
 * {@link #run} writes the request, relays progress, waits for the result or error and for {@code done} (the worker is free again), and
 * enforces the wall-time budget and cancellation from outside: in fork mode only the request's child is killed and the worker stays; in
 * warm mode the whole worker goes. Standard error is drained into the server's log under the worker's id.
 */
final class WorkerProcess implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger("engine.python.worker");
    private static final Map<String, Object> EOF = Map.of("type", "__eof");
    /** The exit code a warm worker's memory watchdog uses (guard.EXIT_MEMORY). */
    static final int EXIT_MEMORY = 86;
    private static final int SIGXCPU = 24;
    private static final int SIGKILL = 9;

    /**
     * How to start a worker.
     *
     * @param id the worker's name in logs and Admin ({@code <node>/<pool>#<n>})
     * @param command the interpreter and its arguments
     * @param env the whole environment (nothing is inherited: no tokens, no credentials)
     * @param cwd its working folder (under the data folder)
     * @param startTimeout how long it may take to say ready
     * @param warm true when requests run in the worker process itself (no fork)
     */
    record Spec(String id, List<String> command, Map<String, String> env, Path cwd, Duration startTimeout, boolean warm) {}

    /** What a run ended with: the result message, or the error; {@code reusable} says whether the worker may take another request. */
    record Outcome(Map<String, Object> result, PoolException error, boolean reusable) {}

    private final Spec spec;
    private final Process process;
    private final BlockingQueue<Map<String, Object>> lines = new LinkedBlockingQueue<>();
    private final ArrayDeque<String> stderrTail = new ArrayDeque<>();
    private final ReentrantLock writeLock = new ReentrantLock();
    private final BufferedWriter stdin;
    private final long startedAt = System.currentTimeMillis();
    private volatile Map<String, Object> ready;
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicInteger served = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long lastUsed = System.currentTimeMillis();

    private WorkerProcess(Spec spec, Process process) {
        this.spec = spec;
        this.process = process;
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread.ofPlatform().daemon().name("engine-python-out-" + spec.id()).start(this::readStdout);
        Thread.ofPlatform().daemon().name("engine-python-err-" + spec.id()).start(this::readStderr);
    }

    /** Starts the worker and waits for it to say ready; a worker that fails to load its engines is refused with its reason. */
    static WorkerProcess start(Spec spec) {
        ProcessBuilder pb = new ProcessBuilder(spec.command());
        pb.environment().clear();
        pb.environment().putAll(spec.env());
        pb.directory(spec.cwd().toFile());
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new PoolException(Code.REFUSED, "the worker could not be started (" + spec.command().get(0) + "): " + e.getMessage()
                    + "; set compute.python to a Python 3.10+ interpreter");
        }
        WorkerProcess w = new WorkerProcess(spec, p);
        long until = System.nanoTime() + spec.startTimeout().toNanos();
        try {
            while (true) {
                long left = until - System.nanoTime();
                Map<String, Object> m = left <= 0 ? null : w.lines.poll(left, TimeUnit.NANOSECONDS);
                if (m == null) {
                    w.kill();
                    throw new PoolException(Code.REFUSED, "worker " + spec.id() + " did not start within " + spec.startTimeout().toSeconds() + " s");
                }
                String t = String.valueOf(m.get("type"));
                if ("ready".equals(t)) {
                    w.ready = m;
                    return w;
                }
                if ("fatal".equals(t) || EOF.equals(m)) {
                    w.kill();
                    String why = "fatal".equals(t) ? String.valueOf(m.get("message")) : "it exited at start: " + w.tail();
                    throw new PoolException(Code.REFUSED, "worker " + spec.id() + " could not start: " + why);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            w.kill();
            throw new PoolException(Code.CANCELLED, "interrupted while starting worker " + spec.id());
        }
    }

    String id() {
        return spec.id();
    }

    long pid() {
        return process.pid();
    }

    Map<String, Object> ready() {
        return ready;
    }

    int served() {
        return served.get();
    }

    long lastUsed() {
        return lastUsed;
    }

    long startedAt() {
        return startedAt;
    }

    boolean alive() {
        return !closed && process.isAlive();
    }

    /**
     * Runs one request. {@code message} is the {@code run} control message; {@code deadlineNanos} the request's end ({@link System#nanoTime});
     * {@code cancelled} is polled while it runs; {@code progress} receives the engine's partial results.
     */
    Outcome run(Map<String, Object> message, String requestId, long deadlineNanos, BooleanSupplier cancelled,
            Consumer<Map<String, Object>> progress) {
        lastUsed = System.currentTimeMillis();
        served.incrementAndGet();
        try {
            send(message);
        } catch (IOException e) {
            kill();
            return new Outcome(null, new PoolException(Code.CRASHED, "the worker is gone (" + e.getMessage() + ")", requestId, null), false);
        }
        long child = -1;
        Map<String, Object> result = null;
        PoolException error = null;
        PoolException stopped = null;                                   // the supervisor stopped it: timeout or cancel
        long graceUntil = Long.MAX_VALUE;
        try {
            while (true) {
                long now = System.nanoTime();
                if (stopped == null && (now >= deadlineNanos || cancelled.getAsBoolean())) {
                    stopped = now >= deadlineNanos
                            ? new PoolException(Code.TIMEOUT, "the computation passed its time budget and was stopped", requestId, null)
                            : new PoolException(Code.CANCELLED, "the computation was cancelled", requestId, null);
                    if (spec.warm() || child <= 0) {
                        kill();
                        return new Outcome(null, stopped, false);
                    }
                    killChild(child);
                    graceUntil = now + TimeUnit.SECONDS.toNanos(5);
                }
                if (now >= graceUntil) {
                    kill();                                                 // the child would not die: replace the whole worker
                    return new Outcome(null, stopped, false);
                }
                Map<String, Object> m = lines.poll(50, TimeUnit.MILLISECONDS);
                if (m == null) {
                    continue;
                }
                if (EOF.equals(m)) {
                    return new Outcome(null, stopped != null ? stopped : died(requestId), false);
                }
                if (!requestId.equals(m.get("id"))) {
                    LOG.log(System.Logger.Level.WARNING, "compute {0}: a message for {1} arrived while running {2}; ignored", spec.id(), m.get("id"), requestId);
                    continue;
                }
                switch (String.valueOf(m.get("type"))) {
                    case "started" -> child = ((Number) m.get("pid")).longValue();
                    case "progress" -> {
                        if (stopped == null) {
                            progress.accept(asMap(m.get("data")));
                        }
                    }
                    case "result" -> result = m;
                    case "error" -> error = engineError(m, requestId);
                    case "done" -> {
                        lastUsed = System.currentTimeMillis();
                        if (stopped != null) {
                            return new Outcome(null, stopped, alive() && !spec.warm());
                        }
                        if (result != null || error != null) {
                            return new Outcome(result, error, alive());
                        }
                        return new Outcome(null, childDied(m, requestId), alive() && !spec.warm());
                    }
                    default -> LOG.log(System.Logger.Level.DEBUG, "compute {0}: unknown message {1}", spec.id(), m.get("type"));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kill();
            return new Outcome(null, new PoolException(Code.CANCELLED, "interrupted", requestId, null), false);
        }
    }

    private static PoolException engineError(Map<String, Object> m, String requestId) {
        String code = String.valueOf(m.get("code"));
        String message = String.valueOf(m.get("message"));
        return switch (code) {
            case "engine" -> new PoolException(Code.ENGINE, message, requestId, null);
            case "limit" -> new PoolException(Code.LIMIT, message, requestId, null);
            default -> new PoolException(Code.FAILED, "the computation failed (request " + requestId + "; the details are in the server's log)",
                    requestId, null);
        };
    }

    private PoolException childDied(Map<String, Object> done, String requestId) {
        Object sig = done.get("signal");
        if (sig instanceof Number n) {
            int s = n.intValue();
            if (s == SIGXCPU) {
                return new PoolException(Code.LIMIT, "the computation passed its CPU-time limit and was stopped", requestId, null);
            }
            if (s == SIGKILL) {
                return new PoolException(Code.LIMIT, "the computation was killed (memory or a limit of the machine)", requestId, null);
            }
            return new PoolException(Code.CRASHED, "the computation crashed (" + done.getOrDefault("signalName", "signal " + s) + ")", requestId, null);
        }
        return new PoolException(Code.CRASHED, "the computation crashed (exit code " + done.get("exit") + ")", requestId, null);
    }

    private PoolException died(String requestId) {
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int code = process.isAlive() ? -1 : process.exitValue();
        closed = true;
        if (code == EXIT_MEMORY) {
            return new PoolException(Code.LIMIT, "the computation passed its memory limit and the worker was stopped", requestId, null);
        }
        LOG.log(System.Logger.Level.WARNING, "compute {0}: the worker died while running {1} (exit {2}): {3}", spec.id(), requestId, code, tail());
        return new PoolException(Code.CRASHED, "the worker crashed (exit code " + code + ")", requestId, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of("value", String.valueOf(o));
    }

    private void send(Map<String, Object> m) throws IOException {
        writeLock.lock();
        try {
            stdin.write(Canonical.json(m));
            stdin.write('\n');
            stdin.flush();
        } finally {
            writeLock.unlock();
        }
    }

    private void killChild(long pid) {
        process.descendants().filter(h -> h.pid() == pid).findFirst().ifPresent(ProcessHandle::destroyForcibly);
    }

    /** Stops the worker and everything it started, at once. */
    void kill() {
        closed = true;
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /** Asks the worker to stop, then makes sure it has. */
    @Override
    void close() {
        if (closed) {
            kill();
            return;
        }
        closed = true;
        try {
            send(Map.of("type", "shutdown"));
            stdin.close();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                kill();
            }
        } catch (IOException e) {
            kill();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kill();
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
    }

    /** The last lines the worker wrote to standard error (for a crash's message in the log). */
    String tail() {
        synchronized (stderrTail) {
            return String.join(" | ", stderrTail);
        }
    }

    private void readStdout() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    lines.add(Json.object(line));
                } catch (IllegalArgumentException e) {
                    LOG.log(System.Logger.Level.WARNING, "compute {0}: a control line that is not JSON was ignored: {1}", spec.id(), line.length() > 200 ? line.substring(0, 200) : line);
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "compute {0}: standard output closed: {1}", spec.id(), e.getMessage());
        } finally {
            lines.add(EOF);
        }
    }

    private void readStderr() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                synchronized (stderrTail) {
                    stderrTail.addLast(line);
                    while (stderrTail.size() > 40) {
                        stderrTail.removeFirst();
                    }
                }
                LOG.log(System.Logger.Level.INFO, "compute {0}: {1}", spec.id(), line);
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "compute {0}: standard error closed: {1}", spec.id(), e.getMessage());
        }
    }
}
