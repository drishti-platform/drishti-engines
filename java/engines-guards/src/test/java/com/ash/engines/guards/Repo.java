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

import com.ash.tools.guards.kit.GuardFiles;
import java.nio.file.Path;
import java.util.List;

/** The repository as the guards read it. */
final class Repo {

    /** The root: the build names it outright ({@code engines.repo.root}), never the folder the JVM started in. */
    static final Path ROOT = Path.of(System.getProperty("engines.repo.root", "../..")).toAbsolutePath().normalize();
    static final Path JAVA = ROOT.resolve("java");

    /** The engine modules. */
    static final List<String> ENGINES = List.of("engine-capacity", "engine-genomics-workbench", "engine-indicators", "engine-query");

    private Repo() {
    }

    static List<Path> files(Path root, String... suffixes) {
        return GuardFiles.files(root, suffixes);
    }

    static String read(Path p) {
        return GuardFiles.read(p);
    }

    static String rel(Path p) {
        return GuardFiles.rel(ROOT, p);
    }
}
