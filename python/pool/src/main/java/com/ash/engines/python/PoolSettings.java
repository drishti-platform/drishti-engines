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

import com.ash.engine.Options;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * The one pool's settings: the descriptor's options of the {@code python} engine, with Drishti's present defaults (its three pools'
 * values, taken once). A host may also build it directly.
 *
 * @param interpreter the Python to run; empty: {@code python3} ({@code python} on Windows)
 * @param workersMin workers kept warm
 * @param workersMax the most workers on this node: configuration only, no limit in the code
 * @param memoryBytes address space per request (0: none)
 * @param cpuSeconds CPU time per request (0: none)
 * @param recycleAfter requests after which a worker is replaced (0: 200, or 50 in warm mode)
 * @param batchShare the share of the workers batch requests may hold at once
 * @param idleStop a worker idle this long is stopped (down to workersMin)
 * @param mode how a worker isolates one request from the next
 * @param isolation {@code standard} or {@code strict} (no network and kernel-held files; Linux, elsewhere no run)
 * @param timeout an interactive or event request's wall-time budget
 * @param timeoutBatch a batch request's wall-time budget
 * @param startTimeout how long a worker may take to say ready
 * @param maxInflightPerUser one person's requests running at once; the rest wait behind other people's
 * @param queueInteractive waiting requests of the class; a full queue answers ENG-5001 at once
 * @param queueEvent as above
 * @param queueBatch as above
 * @param maxResultBytes the largest result a worker may hand back
 * @param maxInputRows the most rows of one frame handed to code
 * @param maxRows the most rows a transform may return
 * @param maxGroups the most groups a measure may return
 * @param packages the allow-list of packages approved code may import (besides the safe standard library)
 */
public record PoolSettings(String interpreter, int workersMin, int workersMax, long memoryBytes, int cpuSeconds, int recycleAfter,
        double batchShare, Duration idleStop, Mode mode, String isolation, Duration timeout, Duration timeoutBatch, Duration startTimeout,
        int maxInflightPerUser, int queueInteractive, int queueEvent, int queueBatch, long maxResultBytes, int maxInputRows, int maxRows,
        int maxGroups, List<String> packages) {

    /** How a worker isolates one request from the next. */
    public enum Mode {
        AUTO, FORK, WARM;

        /** {@code auto} is fork where the platform has it (Linux, macOS), warm on Windows. */
        public Mode resolved() {
            if (this != AUTO) {
                return this;
            }
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? WARM : FORK;
        }
    }

    public PoolSettings {
        interpreter = interpreter == null ? "" : interpreter.strip();
        if (workersMax < 1 || workersMin < 0 || workersMin > workersMax) {
            throw new IllegalArgumentException("workers: min " + workersMin + ", max " + workersMax + " (0 <= min <= max, max >= 1)");
        }
        mode = mode == null ? Mode.AUTO : mode;
        recycleAfter = recycleAfter <= 0 ? (mode.resolved() == Mode.WARM ? 50 : 200) : recycleAfter;
        if (batchShare < 0 || batchShare > 1) {
            throw new IllegalArgumentException("batch-share is between 0 and 1, not " + batchShare);
        }
        isolation = isolation == null || isolation.isBlank() ? "strict" : isolation;
        if (!"standard".equals(isolation) && !"strict".equals(isolation)) {
            throw new IllegalArgumentException("isolation is standard or strict, not '" + isolation + "'");
        }
        if (strict(isolation) && mode.resolved() == Mode.WARM) {
            throw new IllegalArgumentException("strict isolation needs a fresh child per request: mode fork (Linux, macOS), not warm");
        }
        packages = List.copyOf(packages == null ? List.of() : packages);
    }

    private static boolean strict(String isolation) {
        return "strict".equals(isolation);
    }

    /** The shipped defaults: what Drishti's {@code python} pool, Transforms and pivot functions use today. */
    public static PoolSettings defaults() {
        return new PoolSettings("", 0, 4, 6L << 30, 120, 200, 0.5, Duration.ofMinutes(5), Mode.AUTO, "strict", Duration.ofSeconds(60),
                Duration.ofMinutes(10), Duration.ofSeconds(60), 4, 256, 10_000, 100_000, 256L << 20, 50_000, 100_000, 5_000,
                List.of("numpy", "pandas", "pyarrow", "scipy", "polars"));
    }

    /** The settings the descriptor's options give (defaults already filled by the host). */
    public static PoolSettings of(Options o) {
        List<String> packages = o.all().get("packages") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
        return new PoolSettings(o.text("interpreter"), (int) o.integer("workers-min"), (int) o.integer("workers-max"), o.integer("memory-mb") << 20,
                (int) o.integer("cpu-seconds"), (int) o.integer("recycle-after"), o.number("batch-share"),
                Duration.ofSeconds(o.integer("idle-stop-seconds")), Mode.valueOf(o.text("mode").toUpperCase(Locale.ROOT)), o.text("isolation"),
                Duration.ofSeconds(o.integer("timeout-seconds")), Duration.ofSeconds(o.integer("timeout-batch-seconds")),
                Duration.ofSeconds(o.integer("start-timeout-seconds")), (int) o.integer("max-inflight-per-user"),
                (int) o.integer("queue-interactive"), (int) o.integer("queue-event"), (int) o.integer("queue-batch"),
                o.integer("max-result-mb") << 20, (int) o.integer("max-input-rows"), (int) o.integer("max-rows"), (int) o.integer("max-groups"), packages);
    }

    /** True when the workers run in the strict sandbox. */
    public boolean strict() {
        return strict(isolation);
    }

    /** The most batch requests that may run at once: the batch share of the workers, at least one. */
    public int batchSlots() {
        return Math.max(1, (int) Math.floor(workersMax * batchShare));
    }

    /** The wall-time budget of a request of class {@code batch} or not. */
    public Duration budget(boolean batch) {
        return batch ? timeoutBatch : timeout;
    }

    /** The interpreter command: the configured one, else {@code python3} ({@code python} on Windows). */
    public String command() {
        if (!interpreter.isEmpty()) {
            return interpreter;
        }
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? "python" : "python3";
    }

    /** The same settings with another worker range (a node's own count). */
    public PoolSettings withWorkers(int min, int max) {
        return new PoolSettings(interpreter, min, max, memoryBytes, cpuSeconds, recycleAfter, batchShare, idleStop, mode, isolation, timeout,
                timeoutBatch, startTimeout, maxInflightPerUser, queueInteractive, queueEvent, queueBatch, maxResultBytes, maxInputRows, maxRows,
                maxGroups, packages);
    }
}
