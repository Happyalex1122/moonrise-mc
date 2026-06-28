package net.minecraft.server.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class DBConfigProvider {
    private static final String DB_URL = "jdbc:sqlite:server_config.db";

    static {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS config (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """);
        } catch (SQLException e) {
            System.err.println("Failed to initialize database: " + e.getMessage());
            e.printStackTrace();
        }
        
        try {
            MoonriseConfig.init();
        } catch (Exception e) {
            System.err.println("Failed to initialize MoonriseConfig: " + e.getMessage());
        }
    }

    public static String getProperty(String key, String defaultValue) {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            // Check if key exists
            try (PreparedStatement pstmt = conn.prepareStatement("SELECT value FROM config WHERE key = ?")) {
                pstmt.setString(1, key);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("value");
                    }
                }
            }

            // Insert default value if key doesn't exist
            String insertValue = defaultValue != null ? defaultValue : "";
            try (PreparedStatement pstmt = conn.prepareStatement("INSERT INTO config (key, value) VALUES (?, ?)")) {
                pstmt.setString(1, key);
                pstmt.setString(2, insertValue);
                pstmt.executeUpdate();
            }
            return defaultValue;
        } catch (SQLException e) {
            System.err.println("Failed to read/write property from database: " + key);
            e.printStackTrace();
            return defaultValue;
        }
    }
}
