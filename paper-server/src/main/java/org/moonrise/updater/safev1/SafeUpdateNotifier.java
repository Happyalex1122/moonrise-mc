package org.moonrise.updater.safev1;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class SafeUpdateNotifier {

    public static void sendDiscordWebhook(String webhookUrl, String content) {
        if (webhookUrl == null || webhookUrl.isEmpty()) return;
        
        try {
            URL url = new URL(webhookUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);

            com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
            payload.addProperty("content", content);
            String jsonInputString = new com.google.gson.Gson().toJson(payload);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            connection.getResponseCode(); // Just to execute the request
        } catch (Exception e) {
            System.err.println("[SafeUpdateNotifier] Failed to send Discord webhook: " + e.getMessage());
        }
    }

    public static void emitDashboardEvent(String eventType, String jsonPayload) {
        // Stub for future WebSocket/dashboard sync
        System.out.println("[SafeUpdateNotifier] [Dashboard Event] " + eventType + ": " + jsonPayload);
    }
}
