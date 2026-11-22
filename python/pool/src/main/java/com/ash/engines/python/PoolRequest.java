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

import com.ash.engine.RequestClass;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One request to a worker of the pool.
 *
 * @param id the request's id (the scratch folder's name, the worker's message id)
 * @param engine the worker engine, {@code module:Class}: one of the pool's
 * @param kind what the engine is asked ({@code measure}, {@code column}, ... for the python engine; its kinds for any other)
 * @param params JSON values: what the engine reads as {@code req.params}
 * @param user who asked (fair scheduling and the audit); never a credential
 * @param roles the asker's roles
 * @param viewSignature the visibility's signature: one worker request is only ever one viewer's
 * @param cls the request class: interactive, event or batch
 * @param seed the request's seed: random numbers only from it
 * @param scratch the folder holding the inputs as Arrow files in {@code in/}
 * @param inputs name to the Arrow file, relative to the scratch folder
 * @param deadline the request's own deadline (the pool's budget for its class also applies; the earlier wins), or null
 * @param cancelled polled while the request waits and runs
 * @param progress receives the engine's partial results
 * @param cpuSeconds CPU seconds of this request, at most the pool's (0: the pool's)
 */
public record PoolRequest(String id, String engine, String kind, Map<String, Object> params, String user, List<String> roles, String viewSignature,
        RequestClass cls, long seed, Scratch scratch, Map<String, String> inputs, Instant deadline, BooleanSupplier cancelled,
        Consumer<Map<String, Object>> progress, int cpuSeconds) {

    PoolRequest {
        params = Map.copyOf(params == null ? Map.of() : params);
        roles = List.copyOf(roles == null ? List.of() : roles);
        inputs = Map.copyOf(inputs == null ? Map.of() : inputs);
        user = user == null || user.isBlank() ? "anonymous" : user;
        viewSignature = viewSignature == null ? "" : viewSignature;
        cls = cls == null ? RequestClass.INTERACTIVE : cls;
        cancelled = cancelled == null ? () -> false : cancelled;
        progress = progress == null ? m -> { } : progress;
    }
}
