package org.moonrise.updater.safev1;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CompatibilityEvaluatorTest {

    private Path tempDir;
    private CompatibilityEvaluator evaluator;

    @BeforeEach
    public void setup() throws IOException {
        tempDir = Files.createTempDirectory("compatibilityevaluator_test");
        evaluator = new CompatibilityEvaluator(Set.of("trusted-source"));
    }

    @AfterEach
    public void teardown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (java.util.stream.Stream<Path> stream = Files.walk(tempDir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                      .forEach(path -> {
                          try {
                              Files.delete(path);
                          } catch (IOException e) {
                          }
                      });
            }
        }
    }

    private String createValidJar(String name, boolean hasPluginYml) throws IOException, NoSuchAlgorithmException {
        Path jarPath = tempDir.resolve(name);
        try (ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(jarPath.toFile()))) {
            if (hasPluginYml) {
                zout.putNextEntry(new ZipEntry("plugin.yml"));
                zout.write("name: test".getBytes());
                zout.closeEntry();
            } else {
                zout.putNextEntry(new ZipEntry("other.txt"));
                zout.write("test".getBytes());
                zout.closeEntry();
            }
        }

        // compute hash
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = Files.readAllBytes(jarPath);
        byte[] hash = digest.digest(bytes);
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    @Test
    public void testValidJarTrustedSource() throws Exception {
        String hash = createValidJar("valid.jar", true);
        Path jarPath = tempDir.resolve("valid.jar");

        UpdateState state = evaluator.evaluate(jarPath, hash, "trusted-source");
        assertEquals(UpdateState.COMPATIBLE, state);
    }

    @Test
    public void testValidJarUnknownSource() throws Exception {
        String hash = createValidJar("valid2.jar", true);
        Path jarPath = tempDir.resolve("valid2.jar");

        UpdateState state = evaluator.evaluate(jarPath, hash, "unknown-source");
        assertEquals(UpdateState.UNKNOWN, state);
    }

    @Test
    public void testInvalidHash() throws Exception {
        createValidJar("valid3.jar", true);
        Path jarPath = tempDir.resolve("valid3.jar");

        UpdateState state = evaluator.evaluate(jarPath, "invalid-hash", "trusted-source");
        assertEquals(UpdateState.BLOCKED, state);
    }

    @Test
    public void testMissingPluginYml() throws Exception {
        String hash = createValidJar("invalid.jar", false);
        Path jarPath = tempDir.resolve("invalid.jar");

        UpdateState state = evaluator.evaluate(jarPath, hash, "trusted-source");
        assertEquals(UpdateState.BLOCKED, state);
    }
    
    @Test
    public void testMissingFile() {
        UpdateState state = evaluator.evaluate(tempDir.resolve("not-exist.jar"), "hash", "trusted-source");
        assertEquals(UpdateState.BLOCKED, state);
    }
}
