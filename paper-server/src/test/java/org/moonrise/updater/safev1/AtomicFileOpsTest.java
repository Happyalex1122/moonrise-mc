package org.moonrise.updater.safev1;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class AtomicFileOpsTest {

    private Path tempDir;

    @BeforeEach
    public void setup() throws IOException {
        tempDir = Files.createTempDirectory("atomicfileops_test");
    }

    @AfterEach
    public void teardown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> stream = Files.walk(tempDir)) {
                stream.sorted(Comparator.reverseOrder())
                      .forEach(path -> {
                          try {
                              Files.delete(path);
                          } catch (IOException e) {
                              // ignore
                          }
                      });
            }
        }
    }

    @Test
    public void testAtomicWriteJson() throws IOException {
        Path targetFile = tempDir.resolve("test.json");
        UpdateManifest manifest = new UpdateManifest();
        manifest.setBatchId("test-batch");

        AtomicFileOps.atomicWriteJson(targetFile, manifest);

        assertTrue(Files.exists(targetFile));
        String content = Files.readString(targetFile);
        assertTrue(content.contains("test-batch"));
    }

    @Test
    public void testPerformBackup() throws IOException {
        Path sourceDir = tempDir.resolve("plugins");
        Files.createDirectories(sourceDir);
        Path pluginJar = sourceDir.resolve("test-plugin.jar");
        Files.writeString(pluginJar, "dummy content");
        
        Path notJar = sourceDir.resolve("not-a-jar.txt");
        Files.writeString(notJar, "dummy txt");

        Path backupDir = tempDir.resolve("backup");

        AtomicFileOps.performBackup(sourceDir, backupDir);

        assertTrue(Files.exists(backupDir.resolve("test-plugin.jar")));
        assertFalse(Files.exists(backupDir.resolve("not-a-jar.txt")));
    }

    @Test
    public void testPerformRollback() throws IOException {
        Path backupDir = tempDir.resolve("backup");
        Files.createDirectories(backupDir);
        Path backupJar = backupDir.resolve("backup-plugin.jar");
        Files.writeString(backupJar, "backup content");

        Path targetDir = tempDir.resolve("plugins");
        
        AtomicFileOps.performRollback(backupDir, targetDir);

        assertTrue(Files.exists(targetDir.resolve("backup-plugin.jar")));
    }
}
