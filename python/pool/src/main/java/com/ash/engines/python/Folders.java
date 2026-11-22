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

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.stream.Stream;

/** Folder helpers: read-only trees (the extracted worker package), removing a tree, and a folder only the host's user can open. */
final class Folders {

    private Folders() {
    }

    /** Removes write permission from every file and folder of the tree. */
    static void readOnly(Path root) throws IOException {
        walk(root, false);
    }

    private static void walk(Path root, boolean writable) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (Files.isSymbolicLink(p)) {
                    continue;
                }
                File f = p.toFile();
                if (writable) {
                    f.setWritable(true, true);
                } else {
                    f.setWritable(false, false);
                }
            }
        }
    }

    /** Removes a tree, making it writable first; quiet when it is not there. */
    static void delete(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        walk(root, true);
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Creates the folder (and its parents) and, on POSIX, restricts it to the owner (rwx------). */
    static Path privateFolder(Path dir) throws IOException {
        Files.createDirectories(dir);
        try {
            Set<PosixFilePermission> owner = PosixFilePermissions.fromString("rwx------");
            Files.setPosixFilePermissions(dir, owner);
        } catch (UnsupportedOperationException windows) {
            // Windows: the work folder's own ACL applies
        }
        return dir;
    }
}
