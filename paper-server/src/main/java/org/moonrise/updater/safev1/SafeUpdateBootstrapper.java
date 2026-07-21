package org.moonrise.updater.safev1;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Logger;
import java.util.List;

public class SafeUpdateBootstrapper {
    private static final Logger LOGGER = Logger.getLogger(SafeUpdateBootstrapper.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void executeBootRecovery(Path workspaceRoot, Path pluginsDir) {
        try {
            UpdateWorkspace workspace = new UpdateWorkspace(workspaceRoot);
            Path manifestFile = workspace.getManifestsDir().resolve("manifest.json");

            if (!Files.exists(manifestFile)) {
                return;
            }

            UpdateManifest manifest = GSON.fromJson(Files.readString(manifestFile, StandardCharsets.UTF_8), UpdateManifest.class);

            if (manifest.getCurrentState() == UpdateState.PENDING_APPROVAL) {
                LOGGER.info("Applying pending updates...");
                manifest.setCurrentState(UpdateState.APPLYING);
                AtomicFileOps.atomicWriteJson(manifestFile, manifest);
                
                AtomicFileOps.performBackup(pluginsDir, workspace.getBackupsDir());
                
                if (Files.exists(workspace.getCandidatesDir())) {
                    try (java.util.stream.Stream<Path> stream = Files.list(workspace.getCandidatesDir())) {
                        stream.forEach(candidate -> {
                            try {
                                if (Files.isRegularFile(candidate) && candidate.getFileName().toString().endsWith(".jar")) {
                                    Path target = pluginsDir.resolve(candidate.getFileName());
                                    Files.copy(candidate, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                }
                            } catch (IOException e) {
                                LOGGER.severe("Failed to apply candidate: " + candidate);
                            }
                        });
                    }
                }
                
                manifest.setCurrentState(UpdateState.AWAITING_HEALTH);
                AtomicFileOps.atomicWriteJson(manifestFile, manifest);
            } else if (manifest.getCurrentState() == UpdateState.APPLYING) {
                LOGGER.warning("Crash detected during update application. Rolling back...");
                AtomicFileOps.performRollback(workspace.getBackupsDir(), pluginsDir);
                manifest.setCurrentState(UpdateState.RECOVERY_REQUIRED);
                AtomicFileOps.atomicWriteJson(manifestFile, manifest);
            } else if (manifest.getCurrentState() == UpdateState.ROLLBACK_PENDING) {
                LOGGER.warning("Update failed health checks on last boot. Rolling back...");
                AtomicFileOps.performRollback(workspace.getBackupsDir(), pluginsDir);
                manifest.setCurrentState(UpdateState.ROLLED_BACK);
                AtomicFileOps.atomicWriteJson(manifestFile, manifest);
            }
        } catch (Exception e) {
            LOGGER.severe("Failed to execute boot recovery: " + e.getMessage());
        }
    }

    public static void executeHealthGate(Path workspaceRoot, Path pluginsDir) {
        try {
            UpdateWorkspace workspace = new UpdateWorkspace(workspaceRoot);
            Path manifestFile = workspace.getManifestsDir().resolve("manifest.json");

            if (!Files.exists(manifestFile)) {
                return;
            }

            UpdateManifest manifest = GSON.fromJson(Files.readString(manifestFile, StandardCharsets.UTF_8), UpdateManifest.class);

            if (manifest.getCurrentState() == UpdateState.AWAITING_HEALTH) {
                boolean allHealthy = true;

                if (manifest.getPlugins() != null && !manifest.getPlugins().isEmpty()) {
                    for (UpdateManifest.Candidate candidate : manifest.getPlugins()) {
                        String expectedHash = candidate.getExpectedHash();
                        if (expectedHash == null || expectedHash.isEmpty()) {
                            continue;
                        }

                        // Verify if any jar in the plugins dir matches this hash
                        boolean hashMatched = false;
                        if (Files.exists(pluginsDir)) {
                            try (java.util.stream.Stream<Path> stream = Files.list(pluginsDir)) {
                                hashMatched = stream
                                        .filter(p -> p.getFileName().toString().endsWith(".jar"))
                                        .anyMatch(p -> {
                                            try {
                                                return computeSha256(p).equalsIgnoreCase(expectedHash);
                                            } catch (Exception e) {
                                                return false;
                                            }
                                        });
                            }
                        }

                        if (!hashMatched) {
                            LOGGER.severe("Health check failed: Plugin missing or hash mismatch for candidate: " + candidate.getName());
                            allHealthy = false;
                            break;
                        }
                    }
                }

                if (!allHealthy) {
                    manifest.setCurrentState(UpdateState.ROLLBACK_PENDING);
                    AtomicFileOps.atomicWriteJson(manifestFile, manifest);
                    System.exit(75); // EX_TEMPFAIL
                } else {
                    manifest.setCurrentState(UpdateState.HEALTHY);
                    AtomicFileOps.atomicWriteJson(manifestFile, manifest);
                    LOGGER.info("Update health checks passed successfully.");
                }
            }
        } catch (Exception e) {
            LOGGER.severe("Failed to execute health gate: " + e.getMessage());
        }
    }

    private static String computeSha256(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
