package net.minecraft.server.command;

import net.minecraft.server.world.MoonriseWorldManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;

public class WorldCommand extends Command {

    public WorldCommand() {
        super("world", "Manage moonrise worlds", "/world", Arrays.asList("worlds"));
        this.setPermission("moonrise.command.world");
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        if (!sender.hasPermission(this.getPermission())) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "Usage: /world <tp|create|link> ...");
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "tp":
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /world tp <world>");
                    return true;
                }
                if (!(sender instanceof Player)) {
                    sender.sendMessage(ChatColor.RED + "Only players can teleport.");
                    return true;
                }
                Player player = (Player) sender;
                World targetWorld = Bukkit.getWorld(args[1]);
                if (targetWorld == null) {
                    player.sendMessage(ChatColor.RED + "World not found.");
                    return true;
                }
                player.teleport(targetWorld.getSpawnLocation());
                player.sendMessage(ChatColor.GREEN + "Teleported to " + args[1]);
                break;
            case "create":
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /world create <name> <NORMAL/NETHER/END>");
                    return true;
                }
                String name = args[1];
                World.Environment env;
                String envStr = args[2].toUpperCase();
                if (envStr.equals("END")) envStr = "THE_END";
                try {
                    env = World.Environment.valueOf(envStr);
                } catch (IllegalArgumentException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid environment. Use NORMAL, NETHER, or END.");
                    return true;
                }
                sender.sendMessage(ChatColor.YELLOW + "Creating world...");
                MoonriseWorldManager.getInstance().createWorld(name, env);
                sender.sendMessage(ChatColor.GREEN + "World created.");
                break;
            case "link":
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Usage: /world link <source> <NETHER/END> <target>");
                    return true;
                }
                String source = args[1];
                World.Environment linkEnv;
                String linkEnvStr = args[2].toUpperCase();
                if (linkEnvStr.equals("END")) linkEnvStr = "THE_END";
                try {
                    linkEnv = World.Environment.valueOf(linkEnvStr);
                } catch (IllegalArgumentException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid environment.");
                    return true;
                }
                String target = args[3];
                MoonriseWorldManager.getInstance().linkWorld(source, target, linkEnv);
                sender.sendMessage(ChatColor.GREEN + "Linked " + source + " to " + target + " via " + linkEnv.name());
                break;
            default:
                sender.sendMessage(ChatColor.RED + "Unknown subcommand.");
                break;
        }
        return true;
    }
}
