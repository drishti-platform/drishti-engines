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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Names no host: no engine source, resource or case names Drishti's code ({@code com.ash.drishti}), Spring or a servlet type. A Drishti
 * <em>setting</em> ({@code drishti.<key>}) may be named only by the capacity engine, whose whole subject is the settings a Drishti server is
 * started with: they are its data (the planner file, its descriptor's help and its cases), never a class it reaches.
 */
class NamesNoHostTest {

    private static final Pattern CLASSES = Pattern.compile("com[./]ash[./]drishti|org[./]springframework|jakarta[./]servlet|javax[./]servlet");
    private static final Pattern SETTINGS = Pattern.compile("\\bdrishti\\.[a-z]");

    @Test
    void noEngineNamesAHost() {
        List<String> out = new ArrayList<>();
        for (String m : Repo.ENGINES) {
            Path dir = Repo.JAVA.resolve(m);
            List<Path> files = new ArrayList<>(Repo.files(dir.resolve("src/main"), ".java", ".yaml", ".properties"));
            files.addAll(Repo.files(dir.resolve("src/test"), ".java"));
            files.addAll(Repo.files(dir.resolve("tck"), ".yaml"));
            for (Path p : files) {
                String text = Repo.read(p);
                if (CLASSES.matcher(text).find() || !m.equals("engine-capacity") && SETTINGS.matcher(text).find()) {
                    out.add(Repo.rel(p));
                }
            }
        }
        assertEquals(List.of(), out, "sources that name a host");
    }
}
