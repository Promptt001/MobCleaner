# MobCleaner (Paper)

MobCleaner is a lightweight Paper plugin that **removes “stuck” mobs** from **loaded** chunks that are **no longer within simulation distance of any online player**.

This is most useful on servers that keep chunks loaded (spawn chunks, chunk loaders, or other plugins) where entities in those chunks can stop ticking and therefore stop being naturally despawned by Minecraft.

By default, MobCleaner is conservative:

- targets **hostile mobs** (`Entity instanceof Monster`)
- removes only **non-persistent** mobs (`LivingEntity#getRemoveWhenFarAway() == true`)
- skips named and tamed mobs (configurable)

---

## Why this exists

Minecraft’s natural despawn logic is tied to **entity ticking** and **player distance**. When a player disconnects or teleports far away, it’s possible for chunks to remain **loaded** but no longer **entity-ticking**. In that state, hostile mobs can accumulate indefinitely.

MobCleaner enforces the “these mobs should go away when nobody is around” behavior by explicitly removing configured mobs from chunks that are **not covered** by any player.

---

## Compatibility

- **Server:** Paper **1.21.x**
- **Java:** **21** (required by Paper 1.21.x)

---

## Installation

1. Build the plugin or download the jar you built.
2. Copy the jar into your server’s `plugins/` directory.
3. Start/restart the server.
4. Edit `plugins/MobCleaner/config.yml` as needed.
5. Run `/mobcleaner reload` (or restart) after changing config.

---

## Building

```bash
./gradlew build
```

Output:

```
app/build/libs/MobCleaner-2.0.0.jar
```

---

## How it works

MobCleaner triggers a cleanup when:

- a player **logs out** (default)
- a player **teleports far enough** that the *FROM* area would fall outside simulation distance (default)
- optionally, via a **periodic sweep task** (disabled by default)

For an event-driven cleanup, MobCleaner:

1. Computes a square region around the trigger location with a radius equal to the configured **coverage distance** (by default: the world’s simulation distance, in chunks).
2. For each **loaded** chunk in that region:
   - if **any online player** still “covers” it (within the coverage distance), it is skipped
   - otherwise, configured mobs in that chunk are removed

MobCleaner never force-loads chunks.

---

## Configuration

MobCleaner creates `plugins/MobCleaner/config.yml` on first run.

### Key options

- `triggers.on-logout`: clean around the player’s last position after disconnect
- `triggers.on-teleport`: clean around the FROM location after “far” teleports
- `radius.mode`: how “coverage distance” is calculated
  - `simulation-distance` (recommended)
  - `view-distance`
  - `fixed`
- `entities.*`: control what mobs are eligible
- `execution.max-chunks-per-tick`: batch work to avoid tick spikes
- `sweep-task.*`: optional periodic scan of loaded chunks (off by default)

### Example configuration

```yml
triggers:
  on-logout: true
  on-teleport: true

world-filter:
  whitelist: []
  blacklist: ["world_the_end"]

radius:
  mode: simulation-distance
  fixed-chunks: 6
  #per-world:
  #  world_nether:
  #    mode: fixed
  #    fixed-chunks: 4

entities:
  hostile-only: true
  only-remove-when-far-away: true
  ignore-named: true
  ignore-tamed: true
  include: []
  exclude: ["WITHER", "ENDER_DRAGON"]

execution:
  delay-ticks: 1
  max-chunks-per-tick: 0
  logging:
    summary: true
    per-chunk: false

sweep-task:
  enabled: false
  interval-ticks: 1200
  max-chunks-per-run: 500
  worlds: []
```

---

## Commands & permissions

### `/mobcleaner help`
Shows a short help message.

### `/mobcleaner reload`
Reloads `config.yml` and reschedules the optional sweep task.

- Permission: `mobcleaner.admin` (default: OP)

### `/mobcleaner sweep`
Runs a sweep immediately (same logic as the optional periodic sweep task). This inspects up to `sweep-task.max-chunks-per-run` loaded chunks per world.

- Permission: `mobcleaner.admin` (default: OP)

---

## Performance notes

- Event-driven cleanups only scan chunks in a **(2r+1)×(2r+1)** square around the trigger, where `r` is the configured coverage radius.
- If you use a large fixed radius, consider setting `execution.max-chunks-per-tick` (e.g., 50–200) to spread work over multiple ticks.
- The periodic `sweep-task` is intentionally disabled by default. Enable it only if you understand your server’s loaded-chunk behavior and have a reasonable `max-chunks-per-run`.

---

## Safety / gameplay impact

MobCleaner **removes entities**. Make sure your `entities.*` rules match your intent.

If you want it to be extra conservative:

- keep `entities.only-remove-when-far-away: true`
- keep `entities.ignore-named: true`
- keep `entities.ignore-tamed: true`
- maintain an `entities.exclude` list for anything you never want touched

---

## License

This project is licensed under the **Mozilla Public License 2.0** (MPL-2.0). See `LICENSE`.
