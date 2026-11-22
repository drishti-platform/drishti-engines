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

import com.ash.engine.text.Canonical;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The worker package ({@code engine_python}, pure Python) carried in the engine JAR's resources and extracted to
 * {@code <work>/sdk/<hash>/}, once per content: the same package is reused, a new one gets a new folder, and the folder is made
 * read-only. Workers find it on their module path. Nothing is written to the machine's temporary folder, nothing is fetched.
 */
final class Sdk {

    /** The package's files, relative to the resource folder {@value #RESOURCES} (a test checks the list against the sources). */
    static final List<String> FILES = List.of(
            "engine_python/__init__.py",
            "engine_python/arrow.py",
            "engine_python/contract.py",
            "engine_python/engine.py",
            "engine_python/guard.py",
            "engine_python/items.py",
            "engine_python/pivot_fn.py",
            "engine_python/worker.py",
            "engine_python/samples/__init__.py",
            "engine_python/samples/vectors.py",
            "engine_python/samples/paths.py");

    static final String RESOURCES = "engine-python-worker/";

    private Sdk() {
    }

    /** Extracts the package under {@code sdkRoot} (if not there yet) and returns the folder to put on the module path. */
    static Path install(Path sdkRoot, ClassLoader from) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        StringBuilder all = new StringBuilder();
        for (String f : FILES) {
            try (InputStream in = from.getResourceAsStream(RESOURCES + f)) {
                if (in == null) {
                    throw new IllegalStateException("the worker file " + f + " is missing from the python engine's JAR");
                }
                byte[] b = in.readAllBytes();
                all.append(f).append(':').append(Canonical.sha384(b)).append('\n');
                files.put(f, b);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        String hash = Canonical.sha384(all.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        Path dir = sdkRoot.resolve(hash);
        if (Files.isDirectory(dir.resolve("engine_python"))) {
            return dir;
        }
        try {
            Files.createDirectories(sdkRoot);
            Path tmp = Files.createDirectory(sdkRoot.resolve("extracting-" + java.util.UUID.randomUUID()));
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                Path p = tmp.resolve(e.getKey());
                Files.createDirectories(p.getParent());
                Files.write(p, e.getValue());
            }
            try {
                Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException raced) {
                Folders.delete(tmp);                                       // another thread or node extracted it first
                if (!Files.isDirectory(dir)) {
                    throw raced;
                }
            }
            Folders.readOnly(dir);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException("could not extract the python worker under " + sdkRoot, e);
        }
    }
}
