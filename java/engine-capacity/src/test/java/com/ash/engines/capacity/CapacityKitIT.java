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
package com.ash.engines.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.engine.tck.KitRun;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The kit on the packaged JAR, as a host will meet it: every case, every law, the explanations and the workbooks, the gates. */
class CapacityKitIT {

    @Test
    void theEnginePassesTheKit() throws Exception {
        List<KitRun.Report> reports = KitRun.jar(Path.of(System.getProperty("engine.jar")),
                new KitRun.Settings(Path.of(System.getProperty("engine.cases")), Path.of(System.getProperty("engine.kit.out")), null, null));
        assertThat(reports).extracting(KitRun.Report::engine).containsExactly("capacity");
        assertThat(reports.get(0).failures()).isEmpty();
        assertThat(reports.get(0).lawsPassed()).contains("repeat", "threads", "order", "seed", "explain", "isolation");
    }
}
