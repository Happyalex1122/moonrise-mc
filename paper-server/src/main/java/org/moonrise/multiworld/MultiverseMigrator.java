package org.moonrise.multiworld;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

public class MultiverseMigrator {

    public static void migrateIfNeeded() {
        File moonriseConfig = new File("moonrise-worlds.yml");
        if (moonriseConfig.exists()) {
            return;
        }

        File mvFolder = new File("plugins/Multiverse-Core");
        File mvWorldsConfig = new File(mvFolder, "worlds.yml");

        if (mvWorldsConfig.exists()) {
            YamlConfiguration mvConfig = YamlConfiguration.loadConfiguration(mvWorldsConfig);
            ConfigurationSection worldsSection = mvConfig.getConfigurationSection("worlds");
            
            if (worldsSection != null) {
                MoonriseWorldConfig.load(); // Initialize map
                
                for (String worldName : worldsSection.getKeys(false)) {
                    ConfigurationSection worldSec = worldsSection.getConfigurationSection(worldName);
                    if (worldSec == null) continue;
                    
                    String alias = worldSec.getString("alias", "");
                    String gamemode = worldSec.getString("gameMode", worldSec.getString("gamemode", "SURVIVAL"));
                    String difficulty = worldSec.getString("difficulty", "EASY");
                    
                    // Boolean properties might be parsed as strings if quoted in Multiverse configs
                    boolean pvp = parseBooleanProp(worldSec, "pvp", true);
                    String respawnWorld = worldSec.getString("respawnWorld", "");
                    boolean allowWeather = parseBooleanProp(worldSec, "allowWeather", true);
                    boolean hunger = parseBooleanProp(worldSec, "hunger", true);

                    MoonriseWorldConfig.WorldSettings settings = new MoonriseWorldConfig.WorldSettings(
                            alias, gamemode, difficulty, pvp, respawnWorld, allowWeather, hunger
                    );
                    
                    MoonriseWorldConfig.addWorld(worldName, settings);
                }
                
                MoonriseWorldConfig.save();
                
                // Rename Multiverse folder to disable it
                File renamedFolder = new File("plugins/Multiverse-Core-Migrated");
                mvFolder.renameTo(renamedFolder);
            }
        }
    }

    private static boolean parseBooleanProp(ConfigurationSection sec, String key, boolean def) {
        if (sec.isString(key)) {
            return Boolean.parseBoolean(sec.getString(key));
        }
        return sec.getBoolean(key, def);
    }
}
