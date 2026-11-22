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

import java.util.Map;
import org.junit.jupiter.api.Test;

class PoolSettingsTest {

    @Test
    void theDefaultsAreDrishtisPythonPool() {
        PoolSettings s = PoolSettings.defaults();
        assertThat(s.workersMax()).isEqualTo(4);
        assertThat(s.isolation()).isEqualTo("strict");
        assertThat(s.batchSlots()).isEqualTo(2);
        assertThat(s.timeout().toSeconds()).isEqualTo(60);
        assertThat(s.maxInputRows()).isEqualTo(50_000);
    }

    @Test
    void strictNeedsAFreshChildAndWorkersAreBounded() {
        assertThatThrownBy(() -> new PoolSettings("", 0, 1, 0, 0, 0, 0.5, java.time.Duration.ofMinutes(1), PoolSettings.Mode.WARM, "strict",
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1), 1, 1, 1, 1, 1, 1, 1, 1, java.util.List.of()))
                .hasMessageContaining("fresh child");
        assertThatThrownBy(() -> PoolSettings.defaults().withWorkers(5, 2)).hasMessageContaining("max");
    }

    @Test
    void jsonReadsControlLines() {
        Map<String, Object> m = Json.object("{\"type\":\"result\",\"rows\":[{\"a\":1,\"b\":2.5,\"c\":null,\"d\":\"x\\u00e9\"}]}");
        assertThat(m.get("type")).isEqualTo("result");
        assertThat(m.get("rows").toString()).contains("a=1", "b=2.5", "c=null", "xé");
    }
}
