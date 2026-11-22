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
package com.ash.engines.query.exec;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * One grouping set of a plan with its restriction: the cells of {@code dims} among the rows whose {@code fixed} dimensions hold the
 * given members and, for an Others member, whose {@code notInDim} is not one of {@code notIn}. Every total, subtotal, opened node,
 * ignored context and Others member is a unit of its own, so no figure is ever made from the figures shown.
 *
 * @param dims the dimension ids grouped by (calculation groups never: they are not columns)
 * @param fixed dimension id to the member every row of the unit has
 * @param notInDim the dimension of an Others restriction, or null
 * @param notIn the members an Others restriction leaves out
 */
public record Unit(SortedSet<String> dims, SortedMap<String, String> fixed, String notInDim, SortedSet<String> notIn) {

    public Unit {
        dims = java.util.Collections.unmodifiableSortedSet(new TreeSet<>(dims));
        fixed = java.util.Collections.unmodifiableSortedMap(new TreeMap<>(fixed));
        notIn = java.util.Collections.unmodifiableSortedSet(new TreeSet<>(notIn == null ? Set.of() : notIn));
    }

    /** A unit without an Others restriction. */
    public static Unit of(Set<String> dims, Map<String, String> fixed) {
        return new Unit(new TreeSet<>(dims), new TreeMap<>(fixed), null, null);
    }

    /** The same unit with {@code ignored} dimensions taken out of the grouping and the restriction. */
    public Unit ignoring(Set<String> ignored) {
        TreeSet<String> d = new TreeSet<>(dims);
        d.removeAll(ignored);
        TreeMap<String, String> f = new TreeMap<>(fixed);
        ignored.forEach(f::remove);
        boolean keepOthers = notInDim != null && !ignored.contains(notInDim);
        return new Unit(d, f, keepOthers ? notInDim : null, keepOthers ? notIn : null);
    }

    /** Every dimension whose member is known in a cell of the unit: grouped or fixed. */
    public List<String> known() {
        TreeSet<String> all = new TreeSet<>(dims);
        all.addAll(fixed.keySet());
        if (notInDim != null) {
            all.add(notInDim);
        }
        return List.copyOf(all);
    }
}
