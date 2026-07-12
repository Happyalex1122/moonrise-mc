package org.moonrise;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class MoonriseAutoUpdater {

    public static void checkUpdatesAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                URL url = new URL("https://api.github.com/repos/Happyalex1122/moonrise-mc/releases/latest");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setRequestProperty("User-Agent", "Moonrise-Updater");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                if (conn.getResponseCode() == 200) {
                    try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
                        JsonElement element = JsonParser.parseReader(reader);
                        if (element.isJsonObject()) {
                            JsonObject json = element.getAsJsonObject();
                            if (json.has("tag_name")) {
                                String tagName = json.get("tag_name").getAsString();
                                if (tagName.contains("26.1") || tagName.contains("1.0.")) { // GitHub tags are currently v1.0.x
                                    System.err.println("[Moonrise] A new update (" + tagName + ") is available on GitHub! Please check the latest release for bug fixes.");
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Silently ignore update check failures to prevent console spam
            }
        });
    }
}
