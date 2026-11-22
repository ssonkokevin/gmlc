package com.hamiltel.gmlc.consistency;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Repository-level artifacts live outside the Maven module: the committed API
 * contract is in {@code api/} and the cross-repo fixtures in {@code fixtures/}.
 * Tests run with the module directory as the working directory, so the parent
 * is the repository root. {@code -Dgmlc.repo.root=/path/to/gmlc} overrides it.
 */
final class RepoFiles {

    private RepoFiles() {
    }

    static Path find(String relative) {
        List<Path> candidates = new ArrayList<>();
        String root = System.getProperty("gmlc.repo.root");
        if (root != null && !root.isBlank()) {
            candidates.add(Paths.get(root, relative));
        }
        candidates.add(Paths.get("..", relative));
        candidates.add(Paths.get(relative));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("cannot find " + relative + " from "
                + Paths.get("").toAbsolutePath().normalize()
                + "; set -Dgmlc.repo.root to the repository root");
    }
}
