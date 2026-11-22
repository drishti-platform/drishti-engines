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
package com.ash.engines.genomics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.engine.Answer;
import com.ash.engine.Status;
import com.ash.engine.Table;
import com.ash.engine.tck.EngineHarness;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The showcase's own small facts (from Drishti's ShowcaseTest) on the pure parts, and the harness on a tiny study: no JAR, no case file, no host. */
class WorkbenchTest {

    private static final List<Map<String, Object>> SAMPLES = List.of(
            Map.of("SAMPLE_ID", "S1", "COHORT", "C", "TUMOUR_TYPE", "lung", "RESPONSE", "responder", "CLUSTER", "A", "EMB_X", 0.0, "EMB_Y", 0.0),
            Map.of("SAMPLE_ID", "S2", "COHORT", "C", "TUMOUR_TYPE", "lung", "RESPONSE", "non-responder", "CLUSTER", "A", "EMB_X", 1.0, "EMB_Y", 1.0),
            Map.of("SAMPLE_ID", "S3", "COHORT", "C", "TUMOUR_TYPE", "colon", "RESPONSE", "non-responder", "CLUSTER", "B", "EMB_X", 2.0, "EMB_Y", 2.0),
            Map.of("SAMPLE_ID", "S4", "COHORT", "C", "TUMOUR_TYPE", "colon", "RESPONSE", "responder", "CLUSTER", "B", "EMB_X", 3.0, "EMB_Y", 3.0));

    @Test
    void aQuantileIsLinearBetweenTheTwoNearest() {
        assertThat(Designs.q(new double[] {1, 2, 3, 4}, 0.5)).isEqualTo(2.5);
        assertThat(Designs.q(new double[] {5}, 0.95)).isEqualTo(5.0);
    }

    @Test
    void aConsequenceShowsAsTheShowcaseShowsIt() {
        assertThat(Cohort.alteration("stop_gained")).isEqualTo("truncating");
        assertThat(Cohort.alteration("synonymous_variant")).isNull();
        assertThat(Cohort.ALTERATIONS).startsWith("truncating", "splice", "missense");
    }

    @Test
    void aBoxOfThreeNumbersIsRefusedInWords() {
        assertThatThrownBy(() -> Sel.of("C", null, "1,2,3", null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("four numbers");
        assertThat(Sel.of("C", "B,A", null, null).words()).isEqualTo("cohort C, clusters A, B");
    }

    @Test
    void theRoundingIsTheShowcases() {
        assertThat(V.round(19 / 60.0, 4)).isEqualTo(0.3167);
        assertThat(V.responded("Complete response")).isTrue();
        assertThat(V.responded("non-responder")).isFalse();
    }

    @Test
    void aMissingTableIsAnAnswerNamingIt() throws Exception {
        try (var s = EngineHarness.of(GenomicsEngine.class).rows("study-sample", SAMPLES).open()) {
            Answer a = s.ask(List.of("summary"), Map.of("cohort", "C"));
            assertThat(a.status()).isEqualTo(Status.NOT_FOUND);
            assertThat(a.missing()).extracting(m -> m.input()).contains("variant-call");
        }
    }

    @Test
    void theFiguresOfASelectionNarrowWithTheCluster() throws Exception {
        try (var s = EngineHarness.of(GenomicsEngine.class).rows("study-sample", SAMPLES)
                .rows("variant-call", List.of(Map.of("SAMPLE_ID", "S1", "VARIANT_ID", "V1"), Map.of("SAMPLE_ID", "S2", "VARIANT_ID", "V1")))
                .rows("study-variant", List.of(Map.of("VARIANT_ID", "V1", "GENE", "G1", "CONSEQUENCE", "missense_variant")))
                .rows("study-gene", List.of(Map.of("GENE", "G1", "CHROM", "1", "START", 1L, "END", 9L)))
                .rows("cnv-segment", List.of(Map.of("SAMPLE_ID", "S3", "CHROM", "2", "START", 1L, "END", 9L, "CALL", "AMP", "COPY_NUMBER", 7L))).open()) {
            Table t = s.ask(List.of("summary"), Map.of("cohort", "C", "cluster", "A", "gene", "G1")).table("summary");
            assertThat(t.integers("SAMPLES")[0]).isEqualTo(2);
            assertThat(t.integers("COHORT_SAMPLES")[0]).isEqualTo(4);
            assertThat(t.integers("ALTERED")[0]).isEqualTo(2);
            assertThat(t.numbers("RESPONSE_IF_ALTERED")[0]).isEqualTo(0.5);
            assertThat(t.isNull("RESPONSE_IF_NOT", 0)).isTrue();               // nobody is left outside cluster A
        }
    }
}
