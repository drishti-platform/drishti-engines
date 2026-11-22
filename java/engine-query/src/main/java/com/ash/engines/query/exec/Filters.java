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

import com.ash.engine.Filter;
import com.ash.engines.query.expr.Ast;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * A visibility {@link Filter} as a Rachana-EL condition, so a grouped scan can carry it ({@code GroupScan.where} is EL text): open
 * point O4 of QUERY_ENGINE.md. Every form translates; {@code ALL} is no condition (null). The filter compares values as text, so the
 * condition does too.
 */
public final class Filters {

    private Filters() {}

    /** The condition, or null for every row. */
    public static Ast el(Filter f) {
        return switch (f) {
            case null -> null;
            case Filter.All a -> null;
            case Filter.Eq e -> new Ast.Binary("==", new Ast.Ref(e.column()), new Ast.Str(e.value()));
            case Filter.In in -> {
                List<Ast> items = new ArrayList<>();
                new TreeSet<>(in.values()).forEach(v -> items.add(new Ast.Str(v)));
                yield new Ast.In(new Ast.Ref(in.column()), new Ast.ListOf(items), false);
            }
            case Filter.Range r -> {
                Ast w = new Ast.Binary("!=", new Ast.Ref(r.column()), new Ast.Null());
                if (r.fromInclusive() != null) {
                    w = Ast.and(w, new Ast.Binary(">=", new Ast.Ref(r.column()), new Ast.Str(r.fromInclusive())));
                }
                if (r.toInclusive() != null) {
                    w = Ast.and(w, new Ast.Binary("<=", new Ast.Ref(r.column()), new Ast.Str(r.toInclusive())));
                }
                yield w;
            }
            case Filter.And a -> {
                Ast w = new Ast.Bool(true);
                for (Filter x : a.all()) {
                    Ast e = el(x);
                    w = e == null ? w : new Ast.Binary("&&", w, e);
                }
                yield w;
            }
            case Filter.Or o -> {
                Ast w = new Ast.Bool(false);
                for (Filter x : o.any()) {
                    Ast e = el(x);
                    w = new Ast.Binary("||", w, e == null ? new Ast.Bool(true) : e);
                }
                yield w;
            }
            case Filter.Not n -> {
                Ast e = el(n.filter());
                yield new Ast.Unary("!", e == null ? new Ast.Bool(true) : e);
            }
        };
    }
}
