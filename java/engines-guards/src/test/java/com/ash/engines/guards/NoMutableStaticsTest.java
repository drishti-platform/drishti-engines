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
package com.ash.engines.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ash.engine.tck.ClassFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * No mutable static state in any engine (two instances, two hosts or two tests in one JVM share nothing): no static field that is not
 * final, and no static final array or concrete mutable collection. Read from the compiled classes with the kit's own reader.
 */
class NoMutableStaticsTest {

    private static final Set<String> MUTABLE = Set.of("Ljava/util/ArrayList;", "Ljava/util/HashMap;", "Ljava/util/HashSet;", "Ljava/util/LinkedList;",
            "Ljava/util/TreeMap;", "Ljava/util/TreeSet;", "Ljava/util/LinkedHashMap;", "Ljava/util/LinkedHashSet;",
            "Ljava/util/concurrent/ConcurrentHashMap;", "Ljava/lang/StringBuilder;", "Ljava/util/Random;");

    @Test
    void noEngineHoldsMutableStaticState() throws Exception {
        List<String> out = new ArrayList<>();
        for (String m : Repo.ENGINES) {
            Path classes = Repo.JAVA.resolve(m).resolve("target/classes");
            if (!Files.isDirectory(classes)) {
                out.add(m + ": not built");
                continue;
            }
            try (var s = Files.walk(classes)) {
                for (Path p : s.filter(x -> x.toString().endsWith(".class")).sorted().toList()) {
                    ClassFiles.Info info = ClassFiles.read(Files.readAllBytes(p));
                    for (ClassFiles.Field f : info.fields()) {
                        if (f.isStatic() && !f.isSynthetic() && (!f.isFinal() || f.descriptor().startsWith("[") || MUTABLE.contains(f.descriptor()))) {
                            out.add(info.name().replace('/', '.') + "." + f.name() + " " + f.descriptor());
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), out, "mutable static state");
    }
}
