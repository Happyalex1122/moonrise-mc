package org.moonrise.updater;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;
import org.bukkit.configuration.file.YamlConfiguration;
public class MoonrisePluginUpdater {

    private static final Gson GSON = new Gson();

    private static class PluginFileInfo {
        File file;
        String sha1;
        String sha256;
        long murmur2;
        boolean updated = false;
        String filename;

        PluginFileInfo(File file) {
            this.file = file;
            this.filename = file.getName();
            this.sha1 = calculateSHA1(file);
            this.sha256 = calculateSHA256(file);
            this.murmur2 = calculateCurseForgeMurmur2(file);
        }
    }

    public static void runAsyncUpdateCheck(String minecraftVersion) {
        if (!net.minecraft.server.config.MoonriseConfig.enableAutoPluginUpdater) {
            return;
        }

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

                System.out.println("=======================================================");
                System.out.println("[Moonrise] Plugin Auto-Updater is starting...");
                System.out.println("=======================================================");

                List<PluginFileInfo> plugins = new ArrayList<>();
                for (File file : files) {
                    plugins.add(new PluginFileInfo(file));
                }

                int updatedCount = 0;

                // 1. Modrinth
                updatedCount += checkModrinth(plugins, minecraftVersion);

                // 2. Hangar
                updatedCount += checkHangar(plugins);

                // 3. CurseForge
                String cfKey = net.minecraft.server.config.MoonriseConfig.curseforgeApiKey;
                if (cfKey != null && !cfKey.isEmpty()) {
                    updatedCount += checkCurseForge(plugins, minecraftVersion, cfKey);
                }

                // 4. SpigotMC (Spiget via Mappings)
                if (!net.minecraft.server.config.MoonriseConfig.spigotMappings.isEmpty()) {
                    updatedCount += checkSpigotMC(plugins);
                }

                if (updatedCount > 0) {
                    System.out.println(String.format(MoonriseLang.get("updater.success"), updatedCount));
                } else {
                    System.out.println(MoonriseLang.get("updater.no_updates"));
                }
            } catch (Exception e) {
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
                applyPendingUpdates();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static int checkModrinth(List<PluginFileInfo> plugins, String minecraftVersion) {
        int updated = 0;
        List<PluginFileInfo> pending = plugins.stream().filter(p -> !p.updated && p.sha1 != null).collect(Collectors.toList());
        if (pending.isEmpty()) return 0;

        try {
            JsonObject requestBody = new JsonObject();
            JsonArray hashesArray = new JsonArray();
            for (PluginFileInfo p : pending) {
                hashesArray.add(p.sha1);
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
            connection.setRequestProperty("User-Agent", "Moonrise/1.0 (admin@moonrise.org)");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = GSON.toJson(requestBody).getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (InputStream is = connection.getInputStream()) {
                    JsonObject responseJson = GSON.fromJson(new String(is.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
                    for (Map.Entry<String, JsonElement> entry : responseJson.entrySet()) {
                        String originalSha1 = entry.getKey();
                        PluginFileInfo pInfo = pending.stream().filter(p -> p.sha1.equalsIgnoreCase(originalSha1)).findFirst().orElse(null);
                        if (pInfo == null) continue;

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
                            
                            boolean alreadyLatest = false;
                            if (targetFileObj.has("hashes") && targetFileObj.getAsJsonObject("hashes").has("sha1")) {
                                if (targetFileObj.getAsJsonObject("hashes").get("sha1").getAsString().equalsIgnoreCase(originalSha1)) {
                                    alreadyLatest = true;
                                }
                            }

                            if (!alreadyLatest) {
                                if (downloadFile(downloadUrl, pInfo.filename)) {
                                    updated++;
                                }
                            }
                            pInfo.updated = true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Moonrise] Modrinth check failed: " + e.getMessage());
        }
        return updated;
    }

    private static int checkHangar(List<PluginFileInfo> plugins) {
        int updated = 0;
        List<PluginFileInfo> pending = plugins.stream().filter(p -> !p.updated && p.sha256 != null).collect(Collectors.toList());
        
        for (PluginFileInfo pInfo : pending) {
            try {
                URL url = new URL("https://hangar.papermc.io/api/v1/versions/hash/" + pInfo.sha256);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", "Moonrise/1.0 (admin@moonrise.org)");
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);
                
                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    try (InputStream is = connection.getInputStream()) {
                        JsonObject versionInfo = GSON.fromJson(new String(is.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
                        String author = versionInfo.get("author").getAsString();
                        String slug = versionInfo.get("project_slug").getAsString();
                        
                        URL latestUrl = new URL("https://hangar.papermc.io/api/v1/projects/" + author + "/" + slug + "/latestrelease");
                        HttpURLConnection latestConn = (HttpURLConnection) latestUrl.openConnection();
                        latestConn.setRequestMethod("GET");
                        latestConn.setRequestProperty("User-Agent", "Moonrise/1.0");
                        latestConn.setConnectTimeout(3000);
                        latestConn.setReadTimeout(3000);
                        
                        if (latestConn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                            try (InputStream lis = latestConn.getInputStream()) {
                                String latestVersionStr = new String(lis.readAllBytes(), StandardCharsets.UTF_8).trim();
                                String currentVersion = versionInfo.get("name").getAsString();
                                
                                if (!latestVersionStr.equalsIgnoreCase(currentVersion)) {
                                    String downloadUrl = "https://hangar.papermc.io/api/v1/projects/" + author + "/" + slug + "/versions/" + latestVersionStr + "/PAPER/download";
                                    if (downloadFile(downloadUrl, pInfo.filename)) {
                                        updated++;
                                    }
                                }
                                pInfo.updated = true;
                            }
                        } else {
                             pInfo.updated = true;
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore
            }
        }
        return updated;
    }

    private static int checkCurseForge(List<PluginFileInfo> plugins, String minecraftVersion, String apiKey) {
        int updated = 0;
        List<PluginFileInfo> pending = plugins.stream().filter(p -> !p.updated && p.murmur2 != 0).collect(Collectors.toList());
        if (pending.isEmpty()) return 0;
        
        try {
            JsonObject requestBody = new JsonObject();
            JsonArray fingerprints = new JsonArray();
            for (PluginFileInfo p : pending) fingerprints.add(p.murmur2);
            requestBody.add("fingerprints", fingerprints);

            URL url = new URL("https://api.curseforge.com/v1/fingerprints/432");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("x-api-key", apiKey);
            connection.setDoOutput(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = GSON.toJson(requestBody).getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }
            
            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (InputStream is = connection.getInputStream()) {
                    JsonObject responseJson = GSON.fromJson(new String(is.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
                    JsonObject data = responseJson.getAsJsonObject("data");
                    JsonArray exactMatches = data.getAsJsonArray("exactMatches");
                    
                    if (exactMatches != null) {
                        for (JsonElement matchElement : exactMatches) {
                            JsonObject match = matchElement.getAsJsonObject();
                            JsonObject fileObj = match.getAsJsonObject("file");
                            long murmur = fileObj.get("fileFingerprint").getAsLong();
                            
                            PluginFileInfo pInfo = pending.stream().filter(p -> p.murmur2 == murmur).findFirst().orElse(null);
                            if (pInfo == null) continue;
                            
                            JsonArray latestFiles = match.getAsJsonArray("latestFiles");
                            if (latestFiles != null && latestFiles.size() > 0) {
                                JsonObject latestTarget = null;
                                for (JsonElement lfElement : latestFiles) {
                                    JsonObject lf = lfElement.getAsJsonObject();
                                    if (latestTarget == null || lf.get("id").getAsInt() > latestTarget.get("id").getAsInt()) {
                                        latestTarget = lf;
                                    }
                                }
                                
                                if (latestTarget != null) {
                                    int currentId = fileObj.get("id").getAsInt();
                                    int latestId = latestTarget.get("id").getAsInt();
                                    if (latestId > currentId) {
                                        if (latestTarget.has("downloadUrl") && !latestTarget.get("downloadUrl").isJsonNull()) {
                                            String downloadUrl = latestTarget.get("downloadUrl").getAsString();
                                            if (downloadUrl != null && !downloadUrl.isEmpty()) {
                                                if (downloadFile(downloadUrl, pInfo.filename)) {
                                                    updated++;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            pInfo.updated = true;
                        }
                    }
                }
            }
        } catch (Exception e) {
             System.err.println("[Moonrise] CurseForge check failed: " + e.getMessage());
        }
        
        return updated;
    }

    private static int checkSpigotMC(List<PluginFileInfo> plugins) {
        int updated = 0;
        List<PluginFileInfo> pending = plugins.stream().filter(p -> !p.updated).collect(Collectors.toList());
        if (pending.isEmpty()) return 0;

        for (PluginFileInfo pInfo : pending) {
            Integer spigotId = net.minecraft.server.config.MoonriseConfig.spigotMappings.get(pInfo.filename);
            if (spigotId == null) continue;

            String currentVersion = null;
            try (JarFile jar = new JarFile(pInfo.file)) {
                JarEntry entry = jar.getJarEntry("plugin.yml");
                if (entry != null) {
                    try (InputStream is = jar.getInputStream(entry)) {
                        YamlConfiguration yaml = new YamlConfiguration();
                        yaml.load(new java.io.InputStreamReader(is, StandardCharsets.UTF_8));
                        currentVersion = yaml.getString("version");
                    }
                }
            } catch (Exception e) {
                // Cannot read plugin.yml
            }

            if (currentVersion == null) continue;

            try {
                URL url = new URL("https://api.spiget.org/v2/resources/" + spigotId + "/versions/latest");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", "Moonrise/1.0 (admin@moonrise.org)");
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);

                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    try (InputStream is = connection.getInputStream()) {
                        JsonObject versionObj = GSON.fromJson(new String(is.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
                        String latestVersion = versionObj.get("name").getAsString();

                        if (!currentVersion.equalsIgnoreCase(latestVersion)) {
                            String downloadUrl = "https://api.spiget.org/v2/resources/" + spigotId + "/download";
                            if (downloadFile(downloadUrl, pInfo.filename)) {
                                updated++;
                            }
                        }
                        pInfo.updated = true;
                    }
                } else {
                     pInfo.updated = true; // Mark as checked even if failed to avoid repeating
                }
            } catch (Exception e) {
                // Ignore
            }
        }
        return updated;
    }

    private static String calculateSHA1(File file) {
        return calculateHash(file, "SHA-1");
    }

    private static String calculateSHA256(File file) {
        return calculateHash(file, "SHA-256");
    }
    
    private static String calculateHash(File file, String algorithm) {
        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
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
    
    private static long calculateCurseForgeMurmur2(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] allBytes = fis.readAllBytes();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (byte b : allBytes) {
                if (b == 9 || b == 10 || b == 13 || b == 32) {
                    continue;
                }
                baos.write(b);
            }
            return computeMurmur2(baos.toByteArray(), 1);
        } catch (Exception e) {
            return 0;
        }
    }

    private static long computeMurmur2(byte[] data, int seed) {
        int m = 0x5bd1e995;
        int r = 24;
        int length = data.length;
        int h = seed ^ length;

        int index = 0;
        while (length >= 4) {
            int k = (data[index] & 0xFF) |
                    ((data[index + 1] & 0xFF) << 8) |
                    ((data[index + 2] & 0xFF) << 16) |
                    ((data[index + 3] & 0xFF) << 24);

            k *= m;
            k ^= k >>> r;
            k *= m;

            h *= m;
            h ^= k;

            index += 4;
            length -= 4;
        }

        switch (length) {
            case 3:
                h ^= (data[index + 2] & 0xFF) << 16;
            case 2:
                h ^= (data[index + 1] & 0xFF) << 8;
            case 1:
                h ^= data[index] & 0xFF;
                h *= m;
        }

        h ^= h >>> 13;
        h *= m;
        h ^= h >>> 15;

        return h & 0xFFFFFFFFL;
    }

    private static boolean downloadFile(String downloadUrl, String filename) {
        try {
            File updateDir = new File("plugins/update");
            if (!updateDir.exists()) updateDir.mkdirs();
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
                while ((bytesRead = is.read(buffer)) != -1) fos.write(buffer, 0, bytesRead);
            }
            System.out.println(String.format(MoonriseLang.get("updater.downloaded"), filename));
            return true;
        } catch (Exception e) {
            System.err.println(String.format(MoonriseLang.get("updater.failed"), filename, e.getMessage()));
            return false;
        }
    }

    public static void applyPendingUpdates() {
        File updateDir = new File("plugins/update");
        if (!updateDir.exists() || !updateDir.isDirectory()) return;
        File pluginsDir = new File("plugins");
        File[] pending = updateDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (pending == null || pending.length == 0) return;

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
