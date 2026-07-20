package org.moonrise.updater.safev1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class UpdateWorkspace {

    private final Path root;
    private final Path candidatesDir;
    private final Path backupsDir;
    private final Path manifestsDir;

    public UpdateWorkspace(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.candidatesDir = root.resolve("candidates");
        this.backupsDir = root.resolve("backups");
        this.manifestsDir = root.resolve("manifests");

        ensureDirectory(this.candidatesDir);
        ensureDirectory(this.backupsDir);
        ensureDirectory(this.manifestsDir);
    }

    private void ensureDirectory(Path dir) throws IOException {
        if (!dir.toAbsolutePath().normalize().startsWith(root)) {
            throw new SecurityException("Directory traversal attempt detected: " + dir);
        }
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
    }

    public Path getRoot() {
        return root;
    }

    public Path getCandidatesDir() {
        return candidatesDir;
    }

    public Path getBackupsDir() {
        return backupsDir;
    }

    public Path getManifestsDir() {
        return manifestsDir;
    }
    
    public Path resolveCandidate(String filename) {
        Path resolved = candidatesDir.resolve(filename).normalize();
        if (!resolved.startsWith(candidatesDir)) {
             throw new SecurityException("Traversal attempt: " + filename);
        }
        return resolved;
    }
}
