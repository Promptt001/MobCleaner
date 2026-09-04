package dev.promptt.mobcleaner;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central cleaning logic.
 *
 * Everything runs on the main thread (Bukkit API is not thread-safe).
 */
public final class CleanupEngine {

    private final Map<String, Integer> sweepCursorByWorld = new ConcurrentHashMap<>();

    /**
     * Cleans chunks in a square (radius in chunks) around a "trigger" location.
     */
    public void startAreaCleanup(
            JavaPlugin plugin,
            World world,
            int baseChunkX,
            int baseChunkZ,
            UUID ignorePlayerUuid,
            MobCleanerSettings settings,
            String reason
    ) {
        if (!settings.worldFilter.isWorldEnabled(world)) {
            return;
        }

        final int radius = settings.radius.getRadius(world);
        if (radius <= 0) {
            return;
        }

        final int diameter = radius * 2 + 1;
        final int minX = baseChunkX - radius;
        final int minZ = baseChunkZ - radius;
        final int maxX = baseChunkX + radius;
        final int maxZ = baseChunkZ + radius;

        // Pre-mark which chunks in this area are still covered by online players.
        // This avoids an O(chunks * players) inner loop.
        final boolean[] covered = new boolean[diameter * diameter];
        for (Player player : world.getPlayers()) {
            if (ignorePlayerUuid != null && ignorePlayerUuid.equals(player.getUniqueId())) {
                continue;
            }

            final int pcx = player.getLocation().getBlockX() >> 4;
            final int pcz = player.getLocation().getBlockZ() >> 4;

            final int markMinX = Math.max(minX, pcx - radius);
            final int markMaxX = Math.min(maxX, pcx + radius);
            final int markMinZ = Math.max(minZ, pcz - radius);
            final int markMaxZ = Math.min(maxZ, pcz + radius);

            for (int x = markMinX; x <= markMaxX; x++) {
                final int xIdx = x - minX;
                final int row = xIdx * diameter;
                for (int z = markMinZ; z <= markMaxZ; z++) {
                    covered[row + (z - minZ)] = true;
                }
            }
        }

        // Build a compact list of loaded, uncovered chunks.
        final IntPairList candidates = new IntPairList(diameter * diameter);
        for (int cx = minX; cx <= maxX; cx++) {
            final int xIdx = cx - minX;
            final int row = xIdx * diameter;

            for (int cz = minZ; cz <= maxZ; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    continue; // never force-load
                }

                if (covered[row + (cz - minZ)]) {
                    continue;
                }

                candidates.add(cx, cz);
            }
        }

        if (candidates.size() == 0) {
            return;
        }

        final CleanupStats stats = new CleanupStats("area", reason);

        final int maxChunksPerTick = settings.execution.maxChunksPerTick;
        if (maxChunksPerTick <= 0 || candidates.size() <= maxChunksPerTick) {
            runChunkBatch(plugin, world, candidates, 0, candidates.size(), settings, stats);
            logSummary(plugin, settings, world, stats);
            return;
        }

        // Batched execution to reduce worst-case tick impact when radius is large.
        final int[] index = {0};
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int start = index[0];
            if (start >= candidates.size()) {
                return;
            }

            int end = Math.min(start + maxChunksPerTick, candidates.size());
            runChunkBatch(plugin, world, candidates, start, end, settings, stats);
            index[0] = end;

            if (index[0] >= candidates.size()) {
                logSummary(plugin, settings, world, stats);
                BukkitTask self = stats.task;
                if (self != null) {
                    self.cancel();
                }
            }
        }, 0L, 1L);

        stats.task = task;
    }

    /**
     * Optional periodic sweep.
     *
     * Inspects up to sweep-task.max-chunks-per-run loaded chunks per run (globally across worlds).
     */
    public void runSweep(JavaPlugin plugin, MobCleanerSettings settings) {
        if (!settings.sweep.enabled) {
            return;
        }

        final int maxChunks = Math.max(0, settings.sweep.maxChunksPerRun);
        if (maxChunks == 0) {
            return;
        }

        final CleanupStats stats = new CleanupStats("sweep", "scheduled/manual");

        for (World world : plugin.getServer().getWorlds()) {
            if (stats.chunksInspected >= maxChunks) {
                break;
            }
            if (!settings.worldFilter.isWorldEnabled(world)) {
                continue;
            }
            if (!settings.sweep.worlds.isEmpty() && !settings.sweep.worlds.contains(world.getName())) {
                continue;
            }

            final int radius = settings.radius.getRadius(world);
            if (radius <= 0) {
                continue;
            }

            Chunk[] loaded = world.getLoadedChunks();
            if (loaded.length == 0) {
                continue;
            }

            int cursor = sweepCursorByWorld.getOrDefault(world.getName(), 0);
            cursor = Math.floorMod(cursor, loaded.length);

            List<int[]> playerChunkCoords = snapshotPlayerChunkCoords(world);

            int processedThisWorld = 0;
            for (int i = 0; i < loaded.length && stats.chunksInspected < maxChunks; i++) {
                int idx = (cursor + i) % loaded.length;
                Chunk chunk = loaded[idx];

                stats.chunksInspected++;

                if (isChunkCovered(playerChunkCoords, chunk.getX(), chunk.getZ(), radius)) {
                    continue;
                }

                int removed = removeMatchingEntities(chunk, settings.entities);
                if (removed > 0) {
                    stats.chunksWithRemovals++;
                    stats.entitiesRemoved += removed;

                    if (settings.execution.logging.perChunk()) {
                        plugin.getLogger().info("[MobCleaner] Removed " + removed + " entities in "
                                + world.getName() + " chunk " + chunk.getX() + "," + chunk.getZ());
                    }
                }

                processedThisWorld++;
            }

            // Advance cursor so we don't start at chunk 0 every run.
            sweepCursorByWorld.put(world.getName(), cursor + processedThisWorld);
        }

        logSummary(plugin, settings, null, stats);
    }

    private static void runChunkBatch(
            JavaPlugin plugin,
            World world,
            IntPairList coords,
            int start,
            int end,
            MobCleanerSettings settings,
            CleanupStats stats
    ) {
        for (int i = start; i < end; i++) {
            int cx = coords.x(i);
            int cz = coords.z(i);

            if (!world.isChunkLoaded(cx, cz)) {
                continue;
            }

            Chunk chunk = world.getChunkAt(cx, cz);
            stats.chunksInspected++;

            int removed = removeMatchingEntities(chunk, settings.entities);
            if (removed > 0) {
                stats.chunksWithRemovals++;
                stats.entitiesRemoved += removed;

                if (settings.execution.logging.perChunk()) {
                    plugin.getLogger().info("[MobCleaner] Removed " + removed + " entities in "
                            + world.getName() + " chunk " + cx + "," + cz);
                }
            }
        }
    }

    private static int removeMatchingEntities(Chunk chunk, MobCleanerSettings.EntityRules rules) {
        int removed = 0;

        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Player) {
                continue;
            }

            if (!(entity instanceof LivingEntity living)) {
                continue;
            }

            EntityType type = entity.getType();

            if (!rules.include.isEmpty() && !rules.include.contains(type)) {
                continue;
            }

            if (!rules.exclude.isEmpty() && rules.exclude.contains(type)) {
                continue;
            }

            if (rules.hostileOnly && !(entity instanceof Monster)) {
                continue;
            }

            if (rules.onlyRemoveWhenFarAway && !living.getRemoveWhenFarAway()) {
                continue;
            }

            if (rules.ignoreNamed && entity.customName() != null) {
                continue;
            }

            if (rules.ignoreTamed && entity instanceof Tameable tameable && tameable.isTamed()) {
                continue;
            }

            if (rules.ignoreLeashed && living.isLeashed()) {
                continue;
            }

            entity.remove();
            removed++;
        }

        return removed;
    }

    private static List<int[]> snapshotPlayerChunkCoords(World world) {
        List<Player> players = world.getPlayers();
        if (players.isEmpty()) {
            return Collections.emptyList();
        }

        List<int[]> coords = new ArrayList<>(players.size());
        for (Player p : players) {
            coords.add(new int[]{p.getLocation().getBlockX() >> 4, p.getLocation().getBlockZ() >> 4});
        }
        return coords;
    }

    private static boolean isChunkCovered(List<int[]> playerChunkCoords, int chunkX, int chunkZ, int radius) {
        for (int[] c : playerChunkCoords) {
            if (Math.abs(c[0] - chunkX) <= radius && Math.abs(c[1] - chunkZ) <= radius) {
                return true;
            }
        }
        return false;
    }

    private static void logSummary(JavaPlugin plugin, MobCleanerSettings settings, World worldOrNull, CleanupStats stats) {
        if (!settings.execution.logging.summary()) {
            return;
        }

        String scope = (worldOrNull == null) ? "all worlds" : worldOrNull.getName();
        plugin.getLogger().info("[MobCleaner] " + stats.mode + " (" + stats.reason + ") in " + scope
                + ": inspected " + stats.chunksInspected
                + " chunks, removed " + stats.entitiesRemoved
                + " entities (" + stats.chunksWithRemovals + " chunks affected)."
        );
    }

    /** Small, allocation-light int pair list for chunk coordinates. */
    private static final class IntPairList {
        private int[] xs;
        private int[] zs;
        private int size;

        IntPairList(int initialCapacity) {
            int cap = Math.max(16, initialCapacity);
            this.xs = new int[cap];
            this.zs = new int[cap];
        }

        void add(int x, int z) {
            if (size >= xs.length) {
                int newCap = xs.length + (xs.length >> 1) + 1;
                xs = Arrays.copyOf(xs, newCap);
                zs = Arrays.copyOf(zs, newCap);
            }
            xs[size] = x;
            zs[size] = z;
            size++;
        }

        int size() {
            return size;
        }

        int x(int index) {
            return xs[index];
        }

        int z(int index) {
            return zs[index];
        }
    }

    private static final class CleanupStats {
        final String mode;
        final String reason;
        int chunksInspected;
        int chunksWithRemovals;
        int entitiesRemoved;
        BukkitTask task;

        CleanupStats(String mode, String reason) {
            this.mode = mode;
            this.reason = reason;
        }
    }
}
