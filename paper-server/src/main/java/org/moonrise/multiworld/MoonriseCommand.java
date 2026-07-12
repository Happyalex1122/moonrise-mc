package org.moonrise.multiworld;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MoonriseCommand extends Command {

    public MoonriseCommand() {
        super("mv", "Moonrise multiworld command", "/mv <tp|create|list|setspawn>", Arrays.asList("mr"));
        this.setPermission("moonrise.command.mv");
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        if (!sender.hasPermission("moonrise.command.mv")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "Usage: /mv <tp|create|list|setspawn>");
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "tp":
                if (!(sender instanceof Player)) {
                    sender.sendMessage("Players only.");
                    return true;
                }
                Player player = (Player) sender;
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /mv tp <world>");
                    return true;
                }
                World targetWorld = Bukkit.getWorld(args[1]);
                if (targetWorld == null) {
                    sender.sendMessage(ChatColor.RED + "World not found.");
                    return true;
                }
                player.teleport(targetWorld.getSpawnLocation());
                sender.sendMessage(ChatColor.GREEN + "Teleported to " + targetWorld.getName());
                break;

            case "create":
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /mv create <world> <normal|nether|end>");
                    return true;
                }
                String worldName = args[1];
                World.Environment env;
                try {
                    env = World.Environment.valueOf(args[2].toUpperCase());
                } catch (IllegalArgumentException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid environment. Use normal, nether, or end.");
                    return true;
                }
                sender.sendMessage(ChatColor.YELLOW + "Creating world " + worldName + "...");
                Bukkit.createWorld(new WorldCreator(worldName).environment(env));
                
                if (!MoonriseWorldConfig.getWorlds().containsKey(worldName)) {
                    MoonriseWorldConfig.WorldSettings defaultSettings = new MoonriseWorldConfig.WorldSettings(
                            "", "SURVIVAL", "EASY", true, "", true, true
                    );
                    MoonriseWorldConfig.addWorld(worldName, defaultSettings);
                    MoonriseWorldConfig.save();
                }
                sender.sendMessage(ChatColor.GREEN + "World created and saved.");
                break;

            case "list":
                sender.sendMessage(ChatColor.YELLOW + "Loaded worlds:");
                for (World w : Bukkit.getWorlds()) {
                    sender.sendMessage(ChatColor.GREEN + "- " + w.getName() + " (" + w.getEnvironment() + ")");
                }
                break;

            case "setspawn":
                if (!(sender instanceof Player)) {
                    sender.sendMessage("Players only.");
                    return true;
                }
                Player p = (Player) sender;
                Location loc = p.getLocation();
                p.getWorld().setSpawnLocation(loc);
                sender.sendMessage(ChatColor.GREEN + "Spawn location set for world " + p.getWorld().getName() + ".");
                break;

            default:
                sender.sendMessage(ChatColor.RED + "Unknown subcommand.");
                break;
        }

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) throws IllegalArgumentException {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            for (String sub : Arrays.asList("tp", "create", "list", "setspawn")) {
                if (sub.startsWith(partial)) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("tp")) {
            String partial = args[1].toLowerCase();
            for (World w : Bukkit.getWorlds()) {
                if (w.getName().toLowerCase().startsWith(partial)) {
                    completions.add(w.getName());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            String partial = args[2].toLowerCase();
            for (String env : Arrays.asList("normal", "nether", "end")) {
                if (env.startsWith(partial)) {
                    completions.add(env);
                }
            }
        }
        return completions;
    }
}
