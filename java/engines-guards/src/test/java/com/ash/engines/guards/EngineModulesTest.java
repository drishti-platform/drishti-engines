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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The shape of an engine module (EN-52: one module, one JAR, each with its descriptor and kit cases): it registers its engine for
 * ServiceLoader, has the descriptor of each registered engine with a product id and the kit's API version, has kit cases, a unit test and
 * the kit's integration test, and its pom takes the engine API and the xlsx writer as provided, nothing else of the platform.
 */
class EngineModulesTest {

    private static final Pattern PRODUCT = Pattern.compile("(?m)^product: ([a-z][a-z0-9-]*)$");
    private static final Pattern ID = Pattern.compile("(?m)^id: ([a-z][a-z0-9-]*)$");

    @Test
    void everyEngineModuleHasItsDescriptorCasesAndTests() throws Exception {
        List<String> errs = new ArrayList<>();
        for (String m : Repo.ENGINES) {
            Path dir = Repo.JAVA.resolve(m);
            Path services = dir.resolve("src/main/resources/META-INF/services/com.ash.engine.Engine");
            if (!Files.isRegularFile(services)) {
                errs.add(m + ": no ServiceLoader registration");
                continue;
            }
            List<String> classes = Files.readAllLines(services).stream().map(String::strip).filter(l -> !l.isEmpty()).toList();
            assertEquals(1, classes.size(), m + " registers one engine");
            Path folder = dir.resolve("src/main/resources/META-INF/drishti-engine");
            try (var s = Files.list(folder)) {
                List<Path> descriptors = s.filter(p -> p.toString().endsWith(".engine.yaml")).toList();
                assertEquals(1, descriptors.size(), m + " has one descriptor");
                String text = Repo.read(descriptors.get(0));
                Matcher id = ID.matcher(text);
                assertTrue(id.find(), m + ": the descriptor has an id");
                assertTrue(descriptors.get(0).getFileName().toString().equals(id.group(1) + ".engine.yaml"), m + ": the descriptor is named after its id");
                assertTrue(PRODUCT.matcher(text).find(), m + ": the descriptor names its product id (the licence grants it)");
                assertTrue(text.contains("api: \"0.1\""), m + ": the descriptor states the engine API it was built against");
            }
            if (Repo.files(dir.resolve("tck/cases"), ".yaml").isEmpty()) {
                errs.add(m + ": no kit cases");
            }
            if (Repo.files(dir.resolve("src/test"), "Test.java").isEmpty() || Repo.files(dir.resolve("src/test"), "IT.java").isEmpty()) {
                errs.add(m + ": needs a unit test and the kit's integration test");
            }
            String pom = Repo.read(dir.resolve("pom.xml"));
            if (pom.contains("com.ash.drishti") || pom.contains("<scope>compile</scope>")) {
                errs.add(m + ": the pom takes something beyond provided libraries");
            }
        }
        assertEquals(List.of(), errs);
    }
}
