package org.moonrise.multiworld;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class MoonriseWorldConfig {
    private static final File CONFIG_FILE = new File("moonrise-worlds.yml");
    private static YamlConfiguration config;

    public static class WorldSettings {
        public String alias;
        public String gamemode;
        public String difficulty;
        public boolean pvp;
        public String respawnWorld;
        public boolean allowWeather;
        public boolean hunger;

        public WorldSettings(String alias, String gamemode, String difficulty, boolean pvp, String respawnWorld, boolean allowWeather, boolean hunger) {
            this.alias = alias;
            this.gamemode = gamemode;
            this.difficulty = difficulty;
            this.pvp = pvp;
            this.respawnWorld = respawnWorld;
            this.allowWeather = allowWeather;
            this.hunger = hunger;
        }
    }

    private static Map<String, WorldSettings> worlds = new HashMap<>();

    public static void load() {
        if (!CONFIG_FILE.exists()) {
            config = new YamlConfiguration();
            return;
        }
        config = YamlConfiguration.loadConfiguration(CONFIG_FILE);
        worlds.clear();
        
        if (config.contains("worlds")) {
            for (String worldName : config.getConfigurationSection("worlds").getKeys(false)) {
                String path = "worlds." + worldName + ".";
                worlds.put(worldName, new WorldSettings(
                    config.getString(path + "alias", ""),
                    config.getString(path + "gamemode", "SURVIVAL"),
                    config.getString(path + "difficulty", "EASY"),
                    config.getBoolean(path + "pvp", true),
                    config.getString(path + "respawn-world", ""),
                    config.getBoolean(path + "allow-weather", true),
                    config.getBoolean(path + "hunger", true)
                ));
            }
        }
    }

    public static void save() {
        if (config == null) {
            config = new YamlConfiguration();
        }
        config.set("worlds", null); // Clear existing configurations
        for (Map.Entry<String, WorldSettings> entry : worlds.entrySet()) {
            String path = "worlds." + entry.getKey() + ".";
            WorldSettings settings = entry.getValue();
            config.set(path + "alias", settings.alias);
            config.set(path + "gamemode", settings.gamemode);
            config.set(path + "difficulty", settings.difficulty);
            config.set(path + "pvp", settings.pvp);
            config.set(path + "respawn-world", settings.respawnWorld);
            config.set(path + "allow-weather", settings.allowWeather);
            config.set(path + "hunger", settings.hunger);
        }
        try {
            config.save(CONFIG_FILE);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void addWorld(String name, WorldSettings settings) {
        worlds.put(name, settings);
    }
    
    public static Map<String, WorldSettings> getWorlds() {
        return worlds;
    }
}
