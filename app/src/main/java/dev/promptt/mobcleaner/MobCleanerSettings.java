package dev.promptt.mobcleaner;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.*;
import java.util.logging.Logger;

/**
 * Parsed, validated configuration for MobCleaner.
 *
 * Keeping settings in memory avoids repeated config lookups on hot paths.
 */
public final class MobCleanerSettings {

    public final TriggerRules triggers;
    public final WorldFilter worldFilter;
    public final RadiusRules radius;
    public final EntityRules entities;
    public final ExecutionRules execution;
    public final SweepRules sweep;

    private MobCleanerSettings(
            TriggerRules triggers,
            WorldFilter worldFilter,
            RadiusRules radius,
            EntityRules entities,
            ExecutionRules execution,
            SweepRules sweep
    ) {
        this.triggers = triggers;
        this.worldFilter = worldFilter;
        this.radius = radius;
        this.entities = entities;
        this.execution = execution;
        this.sweep = sweep;
    }

    public static MobCleanerSettings load(FileConfiguration config, Logger logger) {
        TriggerRules triggers = TriggerRules.load(config.getConfigurationSection("triggers"));
        WorldFilter worldFilter = WorldFilter.load(config.getConfigurationSection("world-filter"));
        RadiusRules radius = RadiusRules.load(config.getConfigurationSection("radius"), logger);
        EntityRules entities = EntityRules.load(config.getConfigurationSection("entities"), logger);
        ExecutionRules execution = ExecutionRules.load(config.getConfigurationSection("execution"));
        SweepRules sweep = SweepRules.load(config.getConfigurationSection("sweep-task"));
        return new MobCleanerSettings(triggers, worldFilter, radius, entities, execution, sweep);
    }

    // -----------------------------
    // Triggers
    // -----------------------------

    public static final class TriggerRules {
        public final boolean logout;
        public final boolean teleport;

        private TriggerRules(boolean logout, boolean teleport) {
            this.logout = logout;
            this.teleport = teleport;
        }

        public static TriggerRules load(ConfigurationSection s) {
            if (s == null) {
                return new TriggerRules(true, true);
            }
            return new TriggerRules(
                    s.getBoolean("on-logout", true),
                    s.getBoolean("on-teleport", true)
            );
        }

        @Override
        public String toString() {
            return "{logout=" + logout + ", teleport=" + teleport + "}";
        }
    }

    // -----------------------------
    // World filtering
    // -----------------------------

    public static final class WorldFilter {
        public final Set<String> whitelist;
        public final Set<String> blacklist;

        private WorldFilter(Set<String> whitelist, Set<String> blacklist) {
            this.whitelist = whitelist;
            this.blacklist = blacklist;
        }

        public boolean isWorldEnabled(World world) {
            return isWorldEnabled(world.getName());
        }

        public boolean isWorldEnabled(String worldName) {
            if (blacklist.contains(worldName)) {
                return false;
            }
            if (!whitelist.isEmpty()) {
                return whitelist.contains(worldName);
            }
            return true;
        }

        public static WorldFilter load(ConfigurationSection s) {
            if (s == null) {
                return new WorldFilter(Set.of(), Set.of());
            }
            Set<String> whitelist = new HashSet<>(s.getStringList("whitelist"));
            Set<String> blacklist = new HashSet<>(s.getStringList("blacklist"));
            return new WorldFilter(Collections.unmodifiableSet(whitelist), Collections.unmodifiableSet(blacklist));
        }
    }

    // -----------------------------
    // Radius
    // -----------------------------

    public enum RadiusMode {
        SIMULATION_DISTANCE,
        VIEW_DISTANCE,
        FIXED
    }

    public record RadiusSpec(RadiusMode mode, int fixedChunks) {}

    public static final class RadiusRules {
        public final RadiusSpec base;
        public final Map<String, RadiusSpec> perWorld;

        private RadiusRules(RadiusSpec base, Map<String, RadiusSpec> perWorld) {
            this.base = base;
            this.perWorld = perWorld;
        }

        public int getRadius(World world) {
            RadiusSpec spec = perWorld.getOrDefault(world.getName(), base);
            int radius;
            switch (spec.mode()) {
                case FIXED -> radius = Math.max(0, spec.fixedChunks());
                case VIEW_DISTANCE -> radius = Math.max(0, Bukkit.getServer().getViewDistance());
                case SIMULATION_DISTANCE -> radius = getSimulationDistanceSafe(world);
                default -> radius = getSimulationDistanceSafe(world);
            }
            return Math.max(0, radius);
        }

        private static int getSimulationDistanceSafe(World world) {
            try {
                return Math.max(0, world.getSimulationDistance());
            } catch (Throwable ignored) {
                return Math.max(0, Bukkit.getServer().getViewDistance());
            }
        }

        public static RadiusRules load(ConfigurationSection s, Logger logger) {
            if (s == null) {
                return new RadiusRules(new RadiusSpec(RadiusMode.SIMULATION_DISTANCE, 0), Map.of());
            }

            RadiusMode baseMode = parseRadiusMode(s.getString("mode", "simulation-distance"), logger);
            int baseFixed = Math.max(0, s.getInt("fixed-chunks", 0));
            RadiusSpec base = new RadiusSpec(baseMode, baseFixed);

            Map<String, RadiusSpec> perWorld = new HashMap<>();
            ConfigurationSection perWorldSection = s.getConfigurationSection("per-world");
            if (perWorldSection != null) {
                for (String worldName : perWorldSection.getKeys(false)) {
                    ConfigurationSection ws = perWorldSection.getConfigurationSection(worldName);
                    if (ws == null) {
                        continue;
                    }
                    RadiusMode wm = parseRadiusMode(ws.getString("mode", s.getString("mode", "simulation-distance")), logger);
                    int wf = Math.max(0, ws.getInt("fixed-chunks", baseFixed));
                    perWorld.put(worldName, new RadiusSpec(wm, wf));
                }
            }

            return new RadiusRules(base, Collections.unmodifiableMap(perWorld));
        }

        private static RadiusMode parseRadiusMode(String raw, Logger logger) {
            String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            return switch (v) {
                case "simulation-distance", "simulation", "sim" -> RadiusMode.SIMULATION_DISTANCE;
                case "view-distance", "view" -> RadiusMode.VIEW_DISTANCE;
                case "fixed" -> RadiusMode.FIXED;
                default -> {
                    logger.warning("[MobCleaner] Unknown radius.mode '" + raw + "' (defaulting to simulation-distance)");
                    yield RadiusMode.SIMULATION_DISTANCE;
                }
            };
        }
    }

    // -----------------------------
    // Entity filtering
    // -----------------------------

    public static final class EntityRules {
        public final boolean hostileOnly;
        public final boolean onlyRemoveWhenFarAway;
        public final boolean ignoreNamed;
        public final boolean ignoreTamed;
        public final boolean ignoreLeashed;
        public final Set<EntityType> include;
        public final Set<EntityType> exclude;

        private EntityRules(
                boolean hostileOnly,
                boolean onlyRemoveWhenFarAway,
                boolean ignoreNamed,
                boolean ignoreTamed,
                boolean ignoreLeashed,
                Set<EntityType> include,
                Set<EntityType> exclude
        ) {
            this.hostileOnly = hostileOnly;
            this.onlyRemoveWhenFarAway = onlyRemoveWhenFarAway;
            this.ignoreNamed = ignoreNamed;
            this.ignoreTamed = ignoreTamed;
            this.ignoreLeashed = ignoreLeashed;
            this.include = include;
            this.exclude = exclude;
        }

        public static EntityRules load(ConfigurationSection s, Logger logger) {
            if (s == null) {
                return new EntityRules(true, true, true, true, true, Set.of(), Set.of());
            }

            boolean hostileOnly = s.getBoolean("hostile-only", true);
            boolean onlyRemoveWhenFarAway = s.getBoolean("only-remove-when-far-away", true);
            boolean ignoreNamed = s.getBoolean("ignore-named", true);
            boolean ignoreTamed = s.getBoolean("ignore-tamed", true);
            boolean ignoreLeashed = s.getBoolean("ignore-leashed", true);

            Set<EntityType> include = parseEntityTypeSet(s.getStringList("include"), "entities.include", logger);
            Set<EntityType> exclude = parseEntityTypeSet(s.getStringList("exclude"), "entities.exclude", logger);

            return new EntityRules(hostileOnly, onlyRemoveWhenFarAway, ignoreNamed, ignoreTamed, ignoreLeashed, include, exclude);
        }

        private static Set<EntityType> parseEntityTypeSet(List<String> values, String path, Logger logger) {
            if (values == null || values.isEmpty()) {
                return Set.of();
            }

            Set<EntityType> out = EnumSet.noneOf(EntityType.class);
            for (String raw : values) {
                if (raw == null) {
                    continue;
                }
                String key = raw.trim().toUpperCase(Locale.ROOT);
                if (key.isEmpty()) {
                    continue;
                }
                try {
                    out.add(EntityType.valueOf(key));
                } catch (IllegalArgumentException e) {
                    logger.warning("[MobCleaner] Unknown EntityType '" + raw + "' in " + path);
                }
            }
            return Collections.unmodifiableSet(out);
        }
    }

    // -----------------------------
    // Execution / logging
    // -----------------------------

    public record LoggingRules(boolean summary, boolean perChunk) {}

    public static final class ExecutionRules {
        public final int delayTicks;
        public final int maxChunksPerTick;
        public final LoggingRules logging;

        private ExecutionRules(int delayTicks, int maxChunksPerTick, LoggingRules logging) {
            this.delayTicks = delayTicks;
            this.maxChunksPerTick = maxChunksPerTick;
            this.logging = logging;
        }

        public static ExecutionRules load(ConfigurationSection s) {
            if (s == null) {
                return new ExecutionRules(1, 0, new LoggingRules(false, false));
            }

            int delay = Math.max(0, s.getInt("delay-ticks", 1));
            int maxChunks = Math.max(0, s.getInt("max-chunks-per-tick", 0));

            ConfigurationSection ls = s.getConfigurationSection("logging");
            boolean summary = ls != null && ls.getBoolean("summary", false);
            boolean perChunk = ls != null && ls.getBoolean("per-chunk", false);

            return new ExecutionRules(delay, maxChunks, new LoggingRules(summary, perChunk));
        }
    }

    // -----------------------------
    // Sweep task
    // -----------------------------

    public static final class SweepRules {
        public final boolean enabled;
        public final long intervalTicks;
        public final int maxChunksPerRun;
        public final Set<String> worlds;

        private SweepRules(boolean enabled, long intervalTicks, int maxChunksPerRun, Set<String> worlds) {
            this.enabled = enabled;
            this.intervalTicks = intervalTicks;
            this.maxChunksPerRun = maxChunksPerRun;
            this.worlds = worlds;
        }

        public static SweepRules load(ConfigurationSection s) {
            if (s == null) {
                return new SweepRules(false, 1200L, 500, Set.of());
            }

            boolean enabled = s.getBoolean("enabled", false);
            long interval = Math.max(1L, s.getLong("interval-ticks", 1200L));
            int max = Math.max(1, s.getInt("max-chunks-per-run", 500));
            Set<String> worlds = new HashSet<>(s.getStringList("worlds"));

            return new SweepRules(enabled, interval, max, Collections.unmodifiableSet(worlds));
        }
    }
}
