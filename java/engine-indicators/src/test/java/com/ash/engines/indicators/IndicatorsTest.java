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
package com.ash.engines.indicators;

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

/** An author's unit tests: the arithmetic by hand, then the harness with no JAR, no case file and no host. */
class IndicatorsTest {

    private static Map<String, Object> obs(String e, String i, long y, double v) {
        return Map.of("ECONOMY", e, "INDICATOR", i, "YEAR", y, "VALUE", v, "UNIT", "u");
    }

    private static final List<Map<String, Object>> ROWS = List.of(obs("A", "G", 2021, 25), obs("A", "G", 2022, 50), obs("A", "G", 2023, 100),
            obs("B", "G", 2023, 40));

    @Test
    void theFiguresOfASeriesAreByHand() {
        Calc.Series s = Calc.series(List.of(new Object[] {"A", "G", 2021, 25.0, "u"}, new Object[] {"A", "G", 2022, 50.0, "u"}, new Object[] {"A", "G", 2023, 100.0, "u"})).get(0);
        Calc.Figures f = Calc.figures(s, 2);
        assertThat(f.change()).isEqualTo(50.0);
        assertThat(f.pct()).isEqualTo(100.0);
        assertThat(f.cagr()).isEqualTo(100.0);                              // 25 to 100 in 2 years doubles each year
        assertThat(f.mean()).isEqualTo(58.333333333333336);
        assertThat(Calc.figures(s, 7).cagr()).isNull();                      // no value 7 years back
    }

    @Test
    void aYearGivenTwiceKeepsTheLargerValue() {
        Calc.Series s = Calc.series(List.of(new Object[] {"A", "G", 2023, 3.0, "u"}, new Object[] {"A", "G", 2023, 5.0, "u"})).get(0);
        assertThat(s.n()).isEqualTo(1);
        assertThat(s.latest()).isEqualTo(5.0);
    }

    @Test
    void equalValuesShareTheBetterRank() {
        List<Calc.Series> all = Calc.series(List.of(new Object[] {"A", "G", 2023, 5.0, "u"}, new Object[] {"B", "G", 2023, 5.0, "u"}, new Object[] {"C", "G", 2023, 1.0, "u"}));
        assertThat(Calc.ranks(all)).extracting(Calc.Rank::rank).containsExactly(1, 1, 3);
    }

    @Test
    void theHarnessAnswersAndExplains() throws Exception {
        try (var s = EngineHarness.of(IndicatorsEngine.class).rows("observation", ROWS).open()) {
            Answer a = s.ask(List.of("series"), Map.of("years", 2));
            assertThat(a.status()).isEqualTo(Status.COMPLETE);
            Table t = a.table("series");
            assertThat(t.texts("ECONOMY")).containsExactly("A", "B");
            assertThat(t.numbers("CAGR")[0]).isEqualTo(100.0);
            assertThat(t.isNull("CHANGE", 1)).isTrue();                      // B has one value
            Explanation e = s.explain(List.of("series"), Map.of("years", 2), new Cell("series", "CAGR", new TreeMap<>(Map.of("ECONOMY", "A", "INDICATOR", "G"))));
            assertThat(e.steps().get(e.steps().size() - 1).value()).isEqualTo(100.0);
            assertThat(s.workbook(List.of("series"), Map.of("years", 2))).isNotEmpty();
        }
    }
}
