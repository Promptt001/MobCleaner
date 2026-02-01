# Contributing

Thanks for considering contributing!

## Requirements

- Java **21** (matches Paper 1.21.x)
- A Paper 1.21.x server for local testing

## Build

```bash
./gradlew build
```

## Local testing

1. Build the jar (see above)
2. Copy the output jar from `app/build/libs/` into your Paper server’s `plugins/` directory
3. Start/restart the server
4. Use `/mobcleaner reload` after changing config

## Code style

- Keep hot-path code (entity iteration) allocation-light
- Never touch Bukkit/Paper API from async threads
- Prefer feature flags/config toggles over hard-coded behavior
