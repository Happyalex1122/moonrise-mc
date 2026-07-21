package org.moonrise.updater.safev1;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class HttpTransport {
    private static final int TIMEOUT_MS = 10000;

    public static boolean downloadFile(String urlStr, Path targetFile) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "Moonrise/Updater");
            
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                if (targetFile.getParent() != null) {
                    Files.createDirectories(targetFile.getParent());
                }
                
                Path tempFile = targetFile.resolveSibling(targetFile.getFileName().toString() + ".tmp");
                try (InputStream in = connection.getInputStream()) {
                    Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } else {
                System.err.println("[Moonrise] Failed to download file from " + urlStr + ". HTTP response code: " + responseCode);
                return false;
            }
        } catch (Exception e) {
            System.err.println("[Moonrise] Exception while downloading file from " + urlStr + ": " + e.getMessage());
            return false;
        }
    }
}
