package dev.promptt.mobcleaner;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * MobCleaner
 *
 * Removes mobs from loaded chunks that are no longer within simulation distance (or configured radius)
 * of any online player. Triggers are configurable (logout, teleport-away, optional periodic sweep).
 */
public final class MobCleanerPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "mobcleaner.admin";

    private MobCleanerSettings settings;
    private CleanupEngine engine;

    private BukkitTask sweepTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();

        Bukkit.getPluginManager().registerEvents(this, this);

        if (getCommand("mobcleaner") != null) {
            getCommand("mobcleaner").setExecutor(this);
            getCommand("mobcleaner").setTabCompleter(this);
        }

        rescheduleSweepTask();
        getLogger().info(() -> "Enabled. Radius mode=" + settings.radius.base.mode() + ", triggers=" + settings.triggers);
    }

    @Override
    public void onDisable() {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
        getLogger().info("Disabled.");
    }

    private void reloadSettings() {
        reloadConfig();
        this.settings = MobCleanerSettings.load(getConfig(), getLogger());
        this.engine = new CleanupEngine();
    }

    private void rescheduleSweepTask() {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }

        if (!settings.sweep.enabled) {
            return;
        }

        long interval = Math.max(1L, settings.sweep.intervalTicks);
        sweepTask = Bukkit.getScheduler().runTaskTimer(
                this,
                () -> engine.runSweep(this, settings),
                interval,
                interval
        );
        getLogger().info(() -> "Scheduled sweep task every " + interval + " ticks (max " + settings.sweep.maxChunksPerRun + " chunks/run)."
        );
    }

    // -----------------
    // Event triggers
    // -----------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!settings.triggers.logout) {
            return;
        }

        final Player quitter = event.getPlayer();
        final World world = quitter.getWorld();
        if (!settings.worldFilter.isWorldEnabled(world)) {
            return;
        }

        // Snapshot chunk coords now; run later so the player is removed from world.getPlayers().
        final int baseCx = quitter.getLocation().getBlockX() >> 4;
        final int baseCz = quitter.getLocation().getBlockZ() >> 4;
        final UUID ignore = quitter.getUniqueId();

        scheduleAreaCleanup(world, baseCx, baseCz, ignore, "logout");
    }

    /**
     * If the player teleports far enough away that the chunks around the FROM location would
     * fall outside the configured radius, run the same cleanup against those chunks.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (!settings.triggers.teleport) {
            return;
        }

        final Location to = event.getTo();
        if (to == null) {
            return;
        }

        final Location from = event.getFrom();
        final World fromWorld = from.getWorld();
        final World toWorld = to.getWorld();
        if (fromWorld == null || toWorld == null) {
            return;
        }
        if (!settings.worldFilter.isWorldEnabled(fromWorld)) {
            return;
        }

        final int radius = settings.radius.getRadius(fromWorld);
        if (radius <= 0) {
            return;
        }

        // If teleport stays within radius (same world), the old area is still covered by this player.
        if (fromWorld.equals(toWorld)) {
            int fromCx = from.getBlockX() >> 4;
            int fromCz = from.getBlockZ() >> 4;
            int toCx = to.getBlockX() >> 4;
            int toCz = to.getBlockZ() >> 4;

            if (Math.abs(toCx - fromCx) <= radius && Math.abs(toCz - fromCz) <= radius) {
                return;
            }
        }

        final int baseCx = from.getBlockX() >> 4;
        final int baseCz = from.getBlockZ() >> 4;

        scheduleAreaCleanup(fromWorld, baseCx, baseCz, null, "teleport");
    }

    private void scheduleAreaCleanup(World world, int baseCx, int baseCz, UUID ignorePlayer, String reason) {
        int delay = Math.max(0, settings.execution.delayTicks);
        Bukkit.getScheduler().runTaskLater(this, () -> engine.startAreaCleanup(this, world, baseCx, baseCz, ignorePlayer, settings, reason), delay);
    }

    // -----------------
    // Commands
    // -----------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(Component.text("You do not have permission (" + ADMIN_PERMISSION + ").", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                reloadSettings();
                rescheduleSweepTask();
                sender.sendMessage(Component.text("MobCleaner reloaded.", NamedTextColor.GREEN));
                return true;
            }
            case "sweep" -> {
                engine.runSweep(this, settings);
                sender.sendMessage(Component.text("MobCleaner sweep triggered.", NamedTextColor.GREEN));
                return true;
            }
            default -> {
                sendHelp(sender);
                return true;
            }
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(
                Component.text("MobCleaner commands:", NamedTextColor.GOLD)
                        .append(Component.newline())
                        .append(Component.text("/mobcleaner reload", NamedTextColor.YELLOW)
                                .append(Component.text(" - Reload config.yml", NamedTextColor.GRAY)))
                        .append(Component.newline())
                        .append(Component.text("/mobcleaner sweep", NamedTextColor.YELLOW)
                                .append(Component.text("  - Run one sweep pass now (respects sweep-task settings)", NamedTextColor.GRAY)))
        );
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return partial(args[0], List.of("help", "reload", "sweep"));
        }
        return Collections.emptyList();
    }

    private static List<String> partial(String token, List<String> options) {
        if (token == null || token.isEmpty()) {
            return options;
        }
        String t = token.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.startsWith(t)) {
                out.add(o);
            }
        }
        return out;
    }
}
