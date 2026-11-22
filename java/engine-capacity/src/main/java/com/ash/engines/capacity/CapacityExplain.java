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

import com.ash.engine.Cell;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.Explanation;
import com.ash.engine.Step;
import com.ash.engine.Steps;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The explanation of one figure, written from its expression tree: every figure it reads, transitively, in the order they are worked out,
 * each a step whose Rachana-EL formula is its expression over the inputs and coefficients it names ({@code given}) and the earlier steps.
 * A figure that joins text (the command line, the warnings) has no form in the expression language: its step is opaque and says where the
 * workbook holds it.
 */
final class CapacityExplain {

    private CapacityExplain() {
    }

    static Explanation of(Planner planner, Planner.Figure target, Map<String, Object> overrides, Cell cell) throws EngineException {
        Evaluator ev = new Evaluator(planner.cells(overrides));
        List<Step> steps = new ArrayList<>();
        try {
            for (Planner.Figure f : planner.closure(target.name())) {
                Object value = ev.value(f.name());
                if (Expr.opaqueInEl(f.node())) {
                    steps.add(Steps.opaque(f.name(), f.label(), "it joins text, which the expression language has no form for; its expression is " + f.expression(),
                            "Main!" + CapacityBook.cellOf(planner, f), value));
                    continue;
                }
                Map<String, Object> given = new LinkedHashMap<>();
                for (String r : Expr.refs(f.node())) {
                    if (planner.figure(r) == null) {
                        given.put(r, ev.value(r));
                    }
                }
                String formula = Expr.toEl(f.node(), n -> planner.figure(n) != null ? "step." + n : "$." + n);
                steps.add(Steps.of(f.name(), f.label().isBlank() ? f.name() : f.label(), formula, given, value));
            }
        } catch (Evaluator.Failure e) {
            throw new EngineException("ENG-3002", Kind.REFUSED, "the figure " + target.name() + " cannot be worked out: " + e.getMessage());
        }
        Object last = steps.get(steps.size() - 1).value();
        return new Explanation(cell, last, steps);
    }
}
