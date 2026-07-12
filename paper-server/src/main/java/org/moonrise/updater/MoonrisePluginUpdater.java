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
        new Thread(() -> {
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
                                String filename = targetFileObj.get("filename").getAsString();

                                downloadFile(downloadUrl, filename);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore exceptions to prevent crashing the server
                System.err.println("[Moonrise] Async update check failed: " + e.getMessage());
            }
        }, "Moonrise-Plugin-Updater").start();
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

    private static void downloadFile(String downloadUrl, String filename) {
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
            
            System.out.println("[Moonrise] " + filename + " 플러그인의 최신 버전을 다운로드 완료했습니다. 다음 서버 재시작 시 자동으로 교체됩니다.");
        } catch (Exception e) {
            System.err.println("[Moonrise] Failed to download update: " + filename + " - " + e.getMessage());
        }
    }
}
