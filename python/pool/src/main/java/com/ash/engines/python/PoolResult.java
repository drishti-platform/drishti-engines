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

import java.util.List;
import java.util.Map;

/**
 * What a worker answered: small rows (JSON values), the engine's meta, and the names of the files it wrote under {@code out/} (read by
 * {@link Scratch#readOut}, still inside the request's scratch folder).
 */
public record PoolResult(List<Map<String, Object>> rows, Map<String, Object> meta, Map<String, String> files, Map<String, String> tables) {

    @SuppressWarnings("unchecked")
    static PoolResult of(Map<String, Object> m) {
        return new PoolResult(list(m.get("rows")), map(m.get("meta")), strings(m.get("files")), strings(m.get("tables")));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static Map<String, String> strings(Object o) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        }
        return out;
    }
}
