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
package com.ash.engines.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.engine.tck.EngineHarness;
import com.ash.engine.tck.KitRun;
import com.ash.engines.query.exec.Keys;
import com.ash.engines.query.expr.Eval;
import com.ash.engines.query.expr.Parser;
import com.ash.engines.query.plan.TimeWindows;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The query engine's own parts, and every kit group but delivery on the class path. */
class QueryTest {

    @Test
    void formulasPrintBackInOneCanonicalRachanaForm() {
        assertThat(Parser.parse("calc(sum(PNL), where=DESK == 'FX') / sum(NOTIONAL)").el())
                .isEqualTo("(calc(sum(PNL), where = (DESK == 'FX')) / sum(NOTIONAL))");
        assertThat(Parser.parse("count() > 300 ? sum($.PNL) : 0").el()).isEqualTo("((count() > 300) ? sum(PNL) : 0)");
        assertThat(Parser.parse("not PNL < 0 and CCY not in ['USD']").el()).isEqualTo("(!((PNL < 0)) && (CCY not in ['USD']))");
        assertThatThrownBy(() -> Parser.parse("sum(PNL")).hasMessageContaining("expected");
    }

    @Test
    void rowExpressionsFollowRachanaValueRules() {
        Map<String, Object> row = Map.of("A", 2.0, "T", "2026-01-02");
        Eval e = new Eval(row::get, c -> null);
        assertThat(e.eval(Parser.parse("A / 0"))).isNull();
        assertThat(e.eval(Parser.parse("B * 2"))).isNull();
        assertThat(e.eval(Parser.parse("B > 1"))).isEqualTo(false);
        assertThat(e.eval(Parser.parse("T >= '2026-01-01'"))).isEqualTo(true);
        assertThat(e.eval(Parser.parse("null == B"))).isEqualTo(true);
    }

    @Test
    void cellCoordinatesReadBackWithTheirEscapes() {
        Map<String, String> c = Map.of("DESK", "F|X", "REGION", "a=b\\c");
        assertThat(Keys.read(Keys.of(c))).isEqualTo(c);
        assertThat(Keys.of(Map.of())).isEqualTo("*");
    }

    @Test
    void timeWindowsFollowTheCalendar() {
        TimeWindows w = new TimeWindows(LocalDate.parse("2026-03-31"), null, Set.of(LocalDate.parse("2026-03-02")), 4);
        assertThat(w.of("ytd").lo()).isEqualTo(LocalDate.parse("2025-04-01"));
        assertThat(w.of("mtd").lo()).isEqualTo(LocalDate.parse("2026-03-01"));
        TimeWindows.Window prior = w.of("prior_period");
        assertThat(prior.hi()).isEqualTo(LocalDate.parse("2026-02-27"));
    }

    @Test
    void theCasesPassTheKitOnTheClassPath(@TempDir Path out) throws Exception {
        KitRun.Report r = EngineHarness.kit(QueryEngine.class, Path.of("tck/cases"), out);
        assertThat(EngineHarness.failures(r)).isEmpty();
    }
}
