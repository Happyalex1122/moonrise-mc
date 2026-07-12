package org.moonrise.updater;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MoonrisePluginUpdater {

    private static final Gson GSON = new Gson();

    public static void runAsyncUpdateCheck(String minecraftVersion) {
        if (!net.minecraft.server.config.MoonriseConfig.enableAutoPluginUpdater) {
            return;
        }

        // Apply pending updates from previous boot before starting the check
        applyPendingUpdates();

        Thread updaterThread = new Thread(() -> {
            try {
                File pluginsDir = new File("plugins");
                if (!pluginsDir.exists() || !pluginsDir.isDirectory()) {
                    return;
                }

                File[] files = pluginsDir.listFiles((dir, name) -> name.endsWith(".jar"));
                if (files == null || files.length == 0) {
                    return;
                }

                List<String> hashes = new ArrayList<>();
                Map<String, String> hashToFile = new HashMap<>();

                for (File file : files) {
                    String hash = calculateSHA1(file);
                    if (hash != null) {
                        hashes.add(hash);
                        hashToFile.put(hash, file.getName());
                    }
                }

                if (hashes.isEmpty()) {
                    return;
                }

                System.out.println("=======================================================");
                System.out.println("[Moonrise] Plugin Auto-Updater is starting...");
                System.out.println("=======================================================");

                JsonObject requestBody = new JsonObject();
                JsonArray hashesArray = new JsonArray();
                for (String h : hashes) {
                    hashesArray.add(h);
                }
                requestBody.add("hashes", hashesArray);
                requestBody.addProperty("algorithm", "sha1");

                JsonArray loadersArray = new JsonArray();
                loadersArray.add("paper");
                loadersArray.add("spigot");
                loadersArray.add("bukkit");
                requestBody.add("loaders", loadersArray);

                JsonArray gameVersionsArray = new JsonArray();
                gameVersionsArray.add(minecraftVersion);
                requestBody.add("game_versions", gameVersionsArray);

                URL url = new URL("https://api.modrinth.com/v2/version_files/update");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                // Modrinth API recommends a descriptive user-agent
                connection.setRequestProperty("User-Agent", "Moonrise/1.0 (admin@moonrise.org)");
                connection.setDoOutput(true);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = GSON.toJson(requestBody).getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (InputStream is = connection.getInputStream()) {
                        String responseString = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        JsonObject responseJson = GSON.fromJson(responseString, JsonObject.class);

                        int updatedCount = 0;
                        for (Map.Entry<String, JsonElement> entry : responseJson.entrySet()) {
                            // entry.getKey() is the original hash
                            JsonObject versionObj = entry.getValue().getAsJsonObject();
                            JsonArray versionFiles = versionObj.getAsJsonArray("files");

                            if (versionFiles != null && versionFiles.size() > 0) {
                                JsonObject targetFileObj = versionFiles.get(0).getAsJsonObject();
                                for (JsonElement vfElement : versionFiles) {
                                    JsonObject vf = vfElement.getAsJsonObject();
                                    if (vf.has("primary") && vf.get("primary").getAsBoolean()) {
                                        targetFileObj = vf;
                                        break;
                                    }
                                }

                                String downloadUrl = targetFileObj.get("url").getAsString();
                                String originalFilename = hashToFile.get(entry.getKey());
                                if (originalFilename == null) {
                                    originalFilename = targetFileObj.get("filename").getAsString();
                                }

                                boolean success = downloadFile(downloadUrl, originalFilename);
                                if (success) {
                                    updatedCount++;
                                }
                            }
                        }
                        
                        if (updatedCount > 0) {
                            System.out.println(String.format(org.moonrise.updater.MoonriseLang.get("updater.success"), updatedCount));
                        } else {
                            System.out.println(org.moonrise.updater.MoonriseLang.get("updater.no_updates"));
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore exceptions to prevent crashing the server
                System.err.println("[Moonrise] Async update check failed: " + e.getMessage());
            } finally {
                System.out.println("=======================================================");
            }
        }, "Moonrise-Plugin-Updater");
        
        updaterThread.start();
        
        if (Boolean.getBoolean("moonrise.updater.sync")) {
            System.out.println("[Moonrise] Waiting for plugin auto-updater to finish... (-Dmoonrise.updater.sync=true)");
            try {
                updaterThread.join();
                // If we waited synchronously, apply the updates immediately
                applyPendingUpdates();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String calculateSHA1(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] byteArray = new byte[8192];
            int bytesCount;
            while ((bytesCount = fis.read(byteArray)) != -1) {
                digest.update(byteArray, 0, bytesCount);
            }
            byte[] bytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean downloadFile(String downloadUrl, String filename) {
        try {
            File updateDir = new File("plugins/update");
            if (!updateDir.exists()) {
                updateDir.mkdirs();
            }

            File outputFile = new File(updateDir, filename);
            URL url = new URL(downloadUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", "Moonrise/1.0 (admin@moonrise.org)");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);

            try (InputStream is = connection.getInputStream();
                 FileOutputStream fos = new FileOutputStream(outputFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }
            
            System.out.println(String.format(org.moonrise.updater.MoonriseLang.get("updater.downloaded"), filename));
            return true;
        } catch (Exception e) {
            System.err.println(String.format(org.moonrise.updater.MoonriseLang.get("updater.failed"), filename, e.getMessage()));
            return false;
        }
    }

    public static void applyPendingUpdates() {
        File updateDir = new File("plugins/update");
        if (!updateDir.exists() || !updateDir.isDirectory()) {
            return;
        }
        File pluginsDir = new File("plugins");
        File[] pending = updateDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (pending == null || pending.length == 0) {
            return;
        }

        for (File p : pending) {
            File dest = new File(pluginsDir, p.getName());
            try {
                java.nio.file.Files.copy(p.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                p.delete();
                System.out.println("[Moonrise] Applied pending update for: " + p.getName());
            } catch (Exception e) {
                System.err.println("[Moonrise] Failed to apply update for " + p.getName() + ": " + e.getMessage());
            }
        }
    }
}
