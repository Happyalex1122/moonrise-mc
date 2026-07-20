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

public class UpdateWorkspaceTest {

    private Path tempDir;

    @BeforeEach
    public void setup() throws IOException {
        tempDir = Files.createTempDirectory("updateworkspace_test");
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
                          }
                      });
            }
        }
    }

    @Test
    public void testWorkspaceCreation() throws IOException {
        UpdateWorkspace workspace = new UpdateWorkspace(tempDir);
        assertTrue(Files.exists(workspace.getCandidatesDir()));
        assertTrue(Files.exists(workspace.getBackupsDir()));
        assertTrue(Files.exists(workspace.getManifestsDir()));
    }

    @Test
    public void testResolveCandidateValid() throws IOException {
        UpdateWorkspace workspace = new UpdateWorkspace(tempDir);
        Path resolved = workspace.resolveCandidate("test.jar");
        assertEquals(workspace.getCandidatesDir().resolve("test.jar"), resolved);
    }

    @Test
    public void testResolveCandidateTraversal() throws IOException {
        UpdateWorkspace workspace = new UpdateWorkspace(tempDir);
        assertThrows(SecurityException.class, () -> {
            workspace.resolveCandidate("../test.jar");
        });
    }
}
