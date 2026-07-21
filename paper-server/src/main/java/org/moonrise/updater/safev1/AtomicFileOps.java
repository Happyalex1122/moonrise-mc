package org.moonrise.updater.safev1;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

public class AtomicFileOps {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void atomicWriteJson(Path targetFile, Object data) throws IOException {
        Path tempFile = targetFile.resolveSibling(targetFile.getFileName().toString() + ".tmp");
        
        String json = GSON.toJson(data);
        Files.writeString(tempFile, json, StandardCharsets.UTF_8);
        
        Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public static void performBackup(Path sourcePluginsDir, Path targetBackupDir) throws IOException {
        if (!Files.exists(targetBackupDir)) {
            Files.createDirectories(targetBackupDir);
        }
        if (!Files.exists(sourcePluginsDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(sourcePluginsDir)) {
            stream.forEach(source -> {
                try {
                    if (Files.isRegularFile(source) && source.getFileName().toString().endsWith(".jar")) {
                        Path target = targetBackupDir.resolve(source.getFileName());
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    System.err.println("Failed to backup file: " + source + " - " + e.getMessage());
                }
            });
        }
    }

    public static void performRollback(Path sourceBackupDir, Path targetPluginsDir) throws IOException {
        if (!Files.exists(targetPluginsDir)) {
            Files.createDirectories(targetPluginsDir);
        }
        if (!Files.exists(sourceBackupDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(sourceBackupDir)) {
            stream.forEach(source -> {
                try {
                    if (Files.isRegularFile(source) && source.getFileName().toString().endsWith(".jar")) {
                        Path target = targetPluginsDir.resolve(source.getFileName());
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    System.err.println("Failed to rollback file: " + source + " - " + e.getMessage());
                }
            });
        }
    }
}
