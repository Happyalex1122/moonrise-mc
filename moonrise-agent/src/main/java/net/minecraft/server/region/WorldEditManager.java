package net.minecraft.server.region;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.data.CraftBlockData;

public class WorldEditManager implements Listener {

    private static final WorldEditManager INSTANCE = new WorldEditManager();
    
    // Player sessions
    private final Map<UUID, Location> pos1Map = new ConcurrentHashMap<>();
    private final Map<UUID, Location> pos2Map = new ConcurrentHashMap<>();

    private WorldEditManager() {}

    public static WorldEditManager getInstance() {
        return INSTANCE;
    }

    public void registerCommandsAndEvents(Plugin dummyPlugin) {
        try {
            Field commandMapField = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            commandMapField.setAccessible(true);
            CommandMap commandMap = (CommandMap) commandMapField.get(Bukkit.getServer());

            commandMap.register("fastwe", new Pos1Command());
            commandMap.register("fastwe", new Pos2Command());
            commandMap.register("fastwe", new SetCommand());
            commandMap.register("fastwe", new ReplaceCommand());

            Bukkit.getPluginManager().registerEvents(this, dummyPlugin);

            System.out.println("[WorldEditManager] Successfully injected Native Async WorldEdit commands into core.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        
        // Use wooden axe for wand
        if (item != null && item.getType() == Material.WOODEN_AXE) {
            Block block = event.getClickedBlock();
            if (block == null) return;
            
            if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
                pos1Map.put(player.getUniqueId(), block.getLocation());
                player.sendMessage(ChatColor.LIGHT_PURPLE + "Position 1 set to (" + block.getX() + ", " + block.getY() + ", " + block.getZ() + ").");
                event.setCancelled(true);
            } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                pos2Map.put(player.getUniqueId(), block.getLocation());
                player.sendMessage(ChatColor.LIGHT_PURPLE + "Position 2 set to (" + block.getX() + ", " + block.getY() + ", " + block.getZ() + ").");
                event.setCancelled(true);
            }
        }
    }

    private class Pos1Command extends Command {
        public Pos1Command() {
            super("pos1");
            this.setAliases(Arrays.asList("/pos1"));
        }
        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!(sender instanceof Player)) return false;
            Player p = (Player) sender;
            pos1Map.put(p.getUniqueId(), p.getLocation().getBlock().getLocation());
            p.sendMessage(ChatColor.LIGHT_PURPLE + "Position 1 set to your location.");
            return true;
        }
    }

    private class Pos2Command extends Command {
        public Pos2Command() {
            super("pos2");
            this.setAliases(Arrays.asList("/pos2"));
        }
        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!(sender instanceof Player)) return false;
            Player p = (Player) sender;
            pos2Map.put(p.getUniqueId(), p.getLocation().getBlock().getLocation());
            p.sendMessage(ChatColor.LIGHT_PURPLE + "Position 2 set to your location.");
            return true;
        }
    }

    private class SetCommand extends Command {
        public SetCommand() {
            super("set");
            this.setAliases(Arrays.asList("/set"));
        }
        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!(sender instanceof Player)) return false;
            Player p = (Player) sender;
            
            if (args.length < 1) {
                p.sendMessage(ChatColor.RED + "Usage: //set <material>");
                return true;
            }

            Location pos1 = pos1Map.get(p.getUniqueId());
            Location pos2 = pos2Map.get(p.getUniqueId());

            if (pos1 == null || pos2 == null) {
                p.sendMessage(ChatColor.RED + "Make a selection first.");
                return true;
            }
            
            if (pos1.getWorld() != pos2.getWorld() || pos1.getWorld() != p.getWorld()) {
                p.sendMessage(ChatColor.RED + "Selection points must be in the same world.");
                return true;
            }

            Material material = Material.matchMaterial(args[0]);
            if (material == null) {
                p.sendMessage(ChatColor.RED + "Unknown material: " + args[0]);
                return true;
            }

            executeAsyncBlockEdit(p, pos1, pos2, material, null);
            return true;
        }
    }
    
    private class ReplaceCommand extends Command {
        public ReplaceCommand() {
            super("replace");
            this.setAliases(Arrays.asList("/replace"));
        }
        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!(sender instanceof Player)) return false;
            Player p = (Player) sender;
            
            if (args.length < 2) {
                p.sendMessage(ChatColor.RED + "Usage: //replace <from> <to>");
                return true;
            }

            Location pos1 = pos1Map.get(p.getUniqueId());
            Location pos2 = pos2Map.get(p.getUniqueId());

            if (pos1 == null || pos2 == null) {
                p.sendMessage(ChatColor.RED + "Make a selection first.");
                return true;
            }

            Material fromMat = Material.matchMaterial(args[0]);
            Material toMat = Material.matchMaterial(args[1]);
            
            if (fromMat == null || toMat == null) {
                p.sendMessage(ChatColor.RED + "Unknown material.");
                return true;
            }

            executeAsyncBlockEdit(p, pos1, pos2, toMat, fromMat);
            return true;
        }
    }

    private void executeAsyncBlockEdit(Player p, Location p1, Location p2, Material newMat, Material filterMat) {
        int minX = Math.min(p1.getBlockX(), p2.getBlockX());
        int minY = Math.min(p1.getBlockY(), p2.getBlockY());
        int minZ = Math.min(p1.getBlockZ(), p2.getBlockZ());
        
        int maxX = Math.max(p1.getBlockX(), p2.getBlockX());
        int maxY = Math.max(p1.getBlockY(), p2.getBlockY());
        int maxZ = Math.max(p1.getBlockZ(), p2.getBlockZ());
        
        long totalBlocks = (long)(maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        p.sendMessage(ChatColor.AQUA + "Queuing Native Async Block Edit... (" + totalBlocks + " blocks)");

        // Async preparation to not freeze the server while generating the list
        Bukkit.getScheduler().runTaskAsynchronously(getDummyPlugin(), () -> {
            List<FastBlockSetter.BlockChange> changes = new ArrayList<>();
            ServerLevel serverLevel = ((CraftWorld) p1.getWorld()).getHandle();
            
            BlockState nmsState = ((CraftBlockData) Bukkit.createBlockData(newMat)).getState();
            
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        
                        if (filterMat != null) {
                            BlockState currentState = serverLevel.getBlockState(pos);
                            if (currentState.getBukkitMaterial() != filterMat) {
                                continue;
                            }
                        }
                        
                        changes.add(new FastBlockSetter.BlockChange(pos, nmsState));
                    }
                }
            }
            
            Bukkit.getScheduler().runTask(getDummyPlugin(), () -> {
                FastBlockSetter.getInstance().queueTask(serverLevel, changes, () -> {
                    p.sendMessage(ChatColor.GREEN + "Native Async Edit complete! (" + changes.size() + " blocks modified)");
                }, e -> {
                    p.sendMessage(ChatColor.RED + "Error during block edit.");
                    e.printStackTrace();
                });
            });
        });
    }

    private Plugin getDummyPlugin() {
        // Find any loaded plugin to attach scheduler tasks
        Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
        if (plugins.length > 0) return plugins[0];
        throw new IllegalStateException("No plugins loaded to attach scheduler to.");
    }
}
