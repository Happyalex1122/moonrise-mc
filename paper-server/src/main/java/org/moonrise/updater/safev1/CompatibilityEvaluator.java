package org.moonrise.updater.safev1;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class CompatibilityEvaluator {

    private final Set<String> trustedOrigins;

    public CompatibilityEvaluator(Set<String> trustedOrigins) {
        this.trustedOrigins = trustedOrigins;
    }

    public UpdateState evaluate(Path jarPath, String expectedSha256) {
        return evaluate(jarPath, expectedSha256, "unknown");
    }

    public UpdateState evaluate(Path jarPath, String expectedSha256, String providerOrigin) {
        if (jarPath == null || !Files.exists(jarPath) || !Files.isRegularFile(jarPath)) {
            return UpdateState.BLOCKED;
        }

        // 1. Compute SHA-256 hash
        try {
            String actualSha256 = computeSha256(jarPath);
            if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
                return UpdateState.BLOCKED;
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            return UpdateState.BLOCKED;
        }

        // 2. Check for plugin.yml or paper-plugin.yml
        boolean hasPluginYml = false;
        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            ZipEntry pluginYml = zipFile.getEntry("plugin.yml");
            ZipEntry paperPluginYml = zipFile.getEntry("paper-plugin.yml");
            
            if (pluginYml != null || paperPluginYml != null) {
                hasPluginYml = true;
            }
        } catch (Exception e) {
            // Malformed ZIP or other read error
            return UpdateState.BLOCKED;
        }

        if (!hasPluginYml) {
            return UpdateState.BLOCKED;
        }

        // 3. Check provider origin
        if (providerOrigin == null || providerOrigin.trim().isEmpty() || "unknown".equalsIgnoreCase(providerOrigin)) {
            return UpdateState.UNKNOWN;
        }
        
        if (trustedOrigins != null && !trustedOrigins.isEmpty()) {
            if (!trustedOrigins.contains(providerOrigin)) {
                return UpdateState.UNKNOWN;
            }
        }

        return UpdateState.COMPATIBLE;
    }

    private String computeSha256(Path path) throws IOException, NoSuchAlgorithmException {
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
