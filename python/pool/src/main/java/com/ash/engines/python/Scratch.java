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
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A request's scratch folder ({@code <work>/scratch/<request id>/}): the inputs as Arrow files in {@code in/}, the worker's large
 * results in {@code out/}. Readable only by the host's user, and removed when the request ends, whatever happened; folders left by a
 * crash are removed by {@link #sweep} at start.
 */
public final class Scratch implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger("engine.python.scratch");

    private final Path dir;

    private Scratch(Path dir) {
        this.dir = dir;
    }

    /** Creates the request's folder. */
    public static Scratch create(Path root, String requestId) {
        try {
            String safe = requestId.replaceAll("[^A-Za-z0-9._-]", "_");
            Path d = root.resolve(safe).normalize();
            if (!d.getParent().equals(root.normalize())) {
                throw new IllegalArgumentException("a request id names a folder directly under the scratch root");
            }
            if (Files.exists(d)) {
                throw new PoolException(PoolException.Code.REFUSED, "request " + requestId + " is already running");
            }
            Folders.privateFolder(root);
            Folders.privateFolder(d);
            Files.createDirectories(d.resolve("in"));
            return new Scratch(d);
        } catch (IOException e) {
            throw new UncheckedIOException("could not make the scratch folder of " + requestId, e);
        }
    }

    public Path dir() {
        return dir;
    }

    /** The path of a new input file {@code <name>.arrows} and its name relative to the folder, for the worker's message. */
    public Path input(String name) {
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new PoolException(PoolException.Code.REFUSED, "an input name is letters, digits, dots, hyphens and underscores, not '" + name + "'");
        }
        return dir.resolve("in").resolve(name + ".arrows");
    }

    public static String relative(String name) {
        return "in/" + name + ".arrows";
    }

    /** Reads a file the worker named: under {@code out/}, never elsewhere (the worker's answer is untrusted), no bigger than {@code maxBytes}. */
    public byte[] readOut(String relative, long maxBytes) {
        Path outDir = dir.resolve("out").normalize();
        Path p = dir.resolve(relative).normalize();
        if (!p.startsWith(outDir) || Files.isSymbolicLink(p) || !Files.isRegularFile(p)) {
            throw new PoolException(PoolException.Code.INVALID, "the worker named a result file outside its out folder: " + relative);
        }
        try {
            long size = Files.size(p);
            if (size > maxBytes) {
                throw new PoolException(PoolException.Code.INVALID, "result file " + relative + " is " + size + " bytes, over the limit of " + maxBytes);
            }
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new PoolException(PoolException.Code.INVALID, "result file " + relative + " could not be read: " + e.getMessage());
        }
    }

    /** The stamp of a file's bytes, for the input stamps of a run. */
    static String hashOf(Path file) throws IOException {
        return Canonical.sha384(Files.readAllBytes(file));
    }

    /** Name to relative path of every input written so far. */
    Map<String, String> inputs() {
        Map<String, String> out = new LinkedHashMap<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir.resolve("in"), "*.arrows")) {
            for (Path p : s) {
                String n = p.getFileName().toString();
                String name = n.substring(0, n.length() - ".arrows".length());
                out.put(name, relative(name));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new java.util.TreeMap<>(out);
    }

    @Override
    public void close() {
        try {
            Folders.delete(dir);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "the scratch folder {0} could not be removed now ({1}); the next sweep removes it", dir, e.getMessage());
        }
    }

    /** Removes every request folder under the root (at start: nothing is running yet). Returns how many were removed. */
    static int sweep(Path root) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int n = 0;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(root)) {
            for (Path p : s) {
                try {
                    Folders.delete(p);
                    n++;
                } catch (IOException e) {
                    LOG.log(System.Logger.Level.WARNING, "could not remove the leftover scratch folder {0}: {1}", p, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "could not sweep {0}: {1}", root, e.getMessage());
        }
        return n;
    }
}
