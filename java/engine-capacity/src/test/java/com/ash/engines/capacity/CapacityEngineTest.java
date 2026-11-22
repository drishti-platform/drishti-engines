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

import com.ash.engine.Answer;
import com.ash.engine.Cell;
import com.ash.engine.Explanation;
import com.ash.engine.Status;
import com.ash.engine.Table;
import com.ash.engine.tck.EngineHarness;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** An author's unit tests on the harness: no JAR, no case file, no host. The recorded answers are in tck/cases; these are the shapes. */
class CapacityEngineTest {

    private static Table figures(EngineHarness.Opened s, Map<String, Object> params) throws Exception {
        Answer a = s.ask(List.of("figures"), params);
        assertThat(a.status()).isEqualTo(Status.COMPLETE);
        return a.table("figures");
    }

    private static double value(Table t, String name) {
        for (int i = 0; i < t.rows(); i++) {
            if (t.texts("NAME")[i].equals(name)) {
                return t.numbers("VALUE")[i];
            }
        }
        throw new AssertionError("no figure " + name);
    }

    @Test
    void theDescriptorAndThePlannerHaveTheSameInputs() {
        Planner p = Planner.builtIn(CapacityEngine.class);
        assertThat(new CapacityEngine().descriptor().params().keySet()).containsExactlyElementsOf(p.inputs().stream().map(Planner.Input::param).toList());
        assertThat(Planner.paramName("TradesPerFungible")).isEqualTo("trades-per-fungible");
    }

    @Test
    void theProductionShapeIsTheExamplesOfTheFile() throws Exception {
        try (var s = EngineHarness.of(CapacityEngine.class).open()) {
            Table t = figures(s, Map.of());
            assertThat(value(t, "XmxGB")).isEqualTo(10.0);                  // the planner's own "examples: production"
            assertThat(value(t, "SetOpenHeapMB")).isEqualTo(4032.0);
            assertThat(value(t, "RamRecommendedGB")).isEqualTo(24.0);
            Table lines = s.ask(List.of("lines"), Map.of()).table("lines");
            assertThat(lines.texts("NAME")).contains("JvmCommand", "YamlBlock", "GcChoice");
        }
    }

    @Test
    void theBenchmarkShapeChoosesTheCollectorByTheHeap() throws Exception {
        try (var s = EngineHarness.of(CapacityEngine.class).open()) {
            Table t = figures(s, Map.of("positions", 1000000L, "trades", 1000000L, "portfolios", 15000L, "basket-members", 2000000L, "nodes", 27024L,
                    "books-cells", 130000L, "intraday-batches", 0L, "updates-per-batch", 0L, "trade-rows-per-day", 20000L));
            assertThat(value(t, "XmxGB")).isEqualTo(19.0);
            assertThat(value(t, "HbFormula")).isBetween(743.7, 743.9);
        }
    }

    @Test
    void theExplanationEndsAtTheFigureAndTheWorkbookIsWritten() throws Exception {
        try (var s = EngineHarness.of(CapacityEngine.class).open()) {
            Explanation e = s.explain(List.of("figures"), Map.of(), new Cell("figures", "VALUE", new TreeMap<>(Map.of("NAME", "XmxGB"))));
            assertThat(e.steps()).isNotEmpty();
            assertThat(e.steps().get(e.steps().size() - 1).name()).isEqualTo("XmxGB");
            assertThat(e.value()).isEqualTo(10.0);
            assertThat(s.workbook(List.of("figures"), Map.of())).isNotEmpty();
        }
    }
}
