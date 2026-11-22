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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The expression evaluator on small formulas, the text of a number as the planner's workbook writes it, and the three renderings of one tree. */
class EvaluatorTest {

    private static Object eval(String formula, Map<String, Object> cells) {
        Map<String, Object> c = new LinkedHashMap<>(cells);
        return new Evaluator(c).eval(Expr.parse(formula));
    }

    @Test
    void arithmeticAndComparisonFollowTheFile() {
        assertThat(eval("1+2*3", Map.of())).isEqualTo(7.0);
        assertThat(eval("2^3^2", Map.of())).isEqualTo(64.0);                       // left to right, as the planner's other evaluators
        assertThat(eval("-2^2", Map.of())).isEqualTo(4.0);                         // a sign binds tighter than ^ (Excel)
        assertThat(eval("IF(A>1,\"big\",\"small\")", Map.of("A", 2.0))).isEqualTo("big");
        assertThat(eval("\"abc\"=\"ABC\"", Map.of())).isEqualTo(true);              // text is compared without case
        assertThat(eval("MAX(1,5,3)+MIN(4,2)", Map.of())).isEqualTo(7.0);
        assertThat(eval("OR(1>2,3>2)", Map.of())).isEqualTo(true);
    }

    @Test
    void ceilingToleratesTheNoiseOfAFigureThatLandsOnAStep() {
        assertThat(eval("CEILING(4032.0000000000005,64)", Map.of())).isEqualTo(4032.0);
        assertThat(eval("CEILING(4033,64)", Map.of())).isEqualTo(4096.0);
    }

    @Test
    void roundIsHalfToEvenOnTheBinaryValue() {
        assertThat(eval("ROUND(2.5,0)", Map.of())).isEqualTo(2.0);
        assertThat(eval("ROUND(3.5,0)", Map.of())).isEqualTo(4.0);
        assertThat(eval("ROUND(1234.5678,2)", Map.of())).isEqualTo(1234.57);
    }

    @Test
    void aNumberIsWrittenAsTheWorkbookWritesIt() {
        assertThat(Evaluator.number(12345678.0)).isEqualTo("12345678");
        assertThat(Evaluator.number(0.1)).isEqualTo("0.1");
        assertThat(Evaluator.number(1e-5)).isEqualTo("1e-05");
        assertThat(Evaluator.number(1.5e-7)).isEqualTo("1.5e-07");
        assertThat(Evaluator.number(1.5e21)).isEqualTo("1.5e+21");
        assertThat(Evaluator.number(123.456)).isEqualTo("123.456");
        assertThat(Evaluator.number(-0.00042)).isEqualTo("-0.00042");
        assertThat(eval("\"-Xmx\"&XmxGB&\"g\"", Map.of("XmxGB", 10.0))).isEqualTo("-Xmx10g");
    }

    @Test
    void aCycleAndAMissingNameAreRefusedInWords() {
        assertThatThrownBy(() -> new Evaluator(Map.of("A", Expr.parse("B+1"), "B", Expr.parse("A+1"))).value("A")).hasMessageContaining("depends on itself");
        assertThatThrownBy(() -> eval("Missing+1", Map.of())).hasMessageContaining("no name Missing");
        assertThatThrownBy(() -> eval("\"x\"+1", Map.of())).hasMessageContaining("used as a number");
    }

    @Test
    void oneTreeThreeRenderings() {
        Expr.Node n = Expr.parse("CEILING(Nodes*8/BytesPerMB+1,Step)");
        assertThat(Expr.refs(n)).containsExactly("Nodes", "BytesPerMB", "Step");
        assertThat(Expr.toExcel(n)).isEqualTo("(ROUNDUP(((Nodes*8)/BytesPerMB+1)/Step-0.000000000001,0)*Step)".replace("((Nodes*8)/BytesPerMB+1)", "(((Nodes*8)/BytesPerMB)+1)"));
        assertThat(Expr.toEl(n, x -> "$." + x)).contains("ceiling(", "$.Nodes", "$.Step");
        assertThat(Expr.needsText(Expr.parse("\"a\"&B"))).isTrue();
        assertThat(Expr.needsText(n)).isFalse();
        assertThat(Expr.opaqueInEl(Expr.parse("CHAR(10)"))).isTrue();
    }
}
