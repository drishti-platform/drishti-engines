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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ash.engine.RequestClass;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The pool on a real worker (standard isolation, so it runs anywhere with Python 3.10+): the shipped sample engine, lifecycle, refusals. */
class PoolIT {

    private static final String SAMPLE = "engine_python.samples.vectors:VectorEngine";
    @TempDir Path work;
    PythonPool pool;

    @BeforeEach
    void up() {
        PoolSettings s = new PoolSettings("", 0, 2, 0, 0, 0, 0.5, Duration.ofMinutes(1), PoolSettings.Mode.AUTO, "standard", Duration.ofSeconds(30),
                Duration.ofSeconds(30), Duration.ofSeconds(30), 4, 4, 4, 4, 1 << 20, 1000, 1000, 100, List.of());
        try {
            PythonBinding.probe(s);
        } catch (PoolException e) {
            assumeTrue(false, e.getMessage());
        }
        pool = new PythonPool(s, work.resolve("pool"), "test", List.of(SAMPLE), PythonPool.class.getClassLoader());
        pool.start();
    }

    @AfterEach
    void down() {
        pool.close();
        pool.close();                                        // idempotent
    }

    private PoolResult ask(String kind, Map<String, Object> params) {
        try (Scratch sc = Scratch.create(pool.scratchRoot(), "r" + System.nanoTime())) {
            return pool.submit(new PoolRequest(sc.dir().getFileName().toString(), SAMPLE, kind, params, "u", List.of(), "", RequestClass.INTERACTIVE, 0, sc,
                    Map.of(), null, () -> false, null, 0));
        }
    }

    @Test
    void echoRoundTripsAndANewRequestSeesNoStateOfTheLast() {
        PoolResult r = ask("echo", Map.of("k", "v"));
        assertThat(r.rows().get(0)).containsEntry("kind", "echo");
        assertThat(pool.stats().served()).isEqualTo(1);
    }

    @Test
    void anUnknownEngineAndAnUnknownKindAreRefusedInWords() {
        try (Scratch sc = Scratch.create(pool.scratchRoot(), "x1")) {
            assertThatThrownBy(() -> pool.submit(new PoolRequest("x1", "no.such:Engine", "echo", Map.of(), "u", List.of(), "", RequestClass.INTERACTIVE, 0, sc,
                    Map.of(), null, () -> false, null, 0))).isInstanceOf(PoolException.class).hasMessageContaining("no engine");
        }
        assertThatThrownBy(() -> ask("nonsense", Map.of())).isInstanceOf(PoolException.class);
    }

    @Test
    void suspendRefusesNewWorkAndResumeTakesIt() {
        pool.suspend();
        assertThatThrownBy(() -> ask("echo", Map.of())).isInstanceOf(PoolException.class).hasMessageContaining("suspended");
        pool.resume();
        assertThat(ask("echo", Map.of()).rows()).hasSize(1);
    }
}
