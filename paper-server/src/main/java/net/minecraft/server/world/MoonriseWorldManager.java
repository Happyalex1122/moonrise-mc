package net.minecraft.server.world;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class MoonriseWorldManager {
    private static MoonriseWorldManager instance;
    private final File dbFile;
    private Connection connection;

    private MoonriseWorldManager() {
        this.dbFile = new File("worlds.db");
        initDatabase();
    }

    public static MoonriseWorldManager getInstance() {
        if (instance == null) {
            instance = new MoonriseWorldManager();
        }
        return instance;
    }

    private void initDatabase() {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS worlds (" +
                        "name TEXT PRIMARY KEY, " +
                        "env TEXT NOT NULL)");
                stmt.execute("CREATE TABLE IF NOT EXISTS links (" +
                        "source TEXT, " +
                        "target TEXT, " +
                        "env TEXT, " +
                        "PRIMARY KEY (source, target, env))");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void loadAllWorlds() {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name, env FROM worlds")) {
            while (rs.next()) {
                String name = rs.getString("name");
                String envStr = rs.getString("env");
                World.Environment env = World.Environment.valueOf(envStr);
                loadWorldInternal(name, env);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void loadWorldInternal(String name, World.Environment env) {
        Bukkit.getServer().createWorld(new WorldCreator(name).environment(env));
    }

    public void createWorld(String name, World.Environment env) {
        try (PreparedStatement pstmt = connection.prepareStatement("INSERT OR REPLACE INTO worlds (name, env) VALUES (?, ?)")) {
            pstmt.setString(1, name);
            pstmt.setString(2, env.name());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        loadWorldInternal(name, env);
    }

    public void loadWorld(String name) {
        try (PreparedStatement pstmt = connection.prepareStatement("SELECT env FROM worlds WHERE name = ?")) {
            pstmt.setString(1, name);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String envStr = rs.getString("env");
                    World.Environment env = World.Environment.valueOf(envStr);
                    loadWorldInternal(name, env);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void linkWorld(String source, String target, World.Environment env) {
        try (PreparedStatement pstmt = connection.prepareStatement("INSERT OR REPLACE INTO links (source, target, env) VALUES (?, ?, ?)")) {
            pstmt.setString(1, source);
            pstmt.setString(2, target);
            pstmt.setString(3, env.name());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public String getLinkedWorld(String source, World.Environment env) {
        try (PreparedStatement pstmt = connection.prepareStatement("SELECT target FROM links WHERE source = ? AND env = ?")) {
            pstmt.setString(1, source);
            pstmt.setString(2, env.name());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("target");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public String getLinkedDimension(String source, World.Environment env) {
        return getLinkedWorld(source, env);
    }
}
