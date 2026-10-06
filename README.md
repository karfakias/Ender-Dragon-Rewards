# Dragon Rewards

Dragon Rewards is made for SMPs where the End is limited, world borders are used, or server owners want dragon fights to stay useful after the first kill.

Version **26.3.0** targets **Minecraft Java 26.3**, with **Java 25**, **Fabric Loader 0.19.5+**, and **Fabric API 0.162.0+26.3 or newer for Minecraft 26.3**. Install the regular `dragonrewards-26.3.0.jar` on the server; players do not need this mod on their clients.

When the dragon dies, the mod independently rolls each enabled reward:

| Reward | Default chance | After a miss |
| --- | --- | --- |
| Elytra | 10% | +5 percentage points, capped at 100% |
| Dragon Head | 20% | +5 percentage points, capped at 100% |
| Enchanted Golden Apple (egap) | 30% | Fixed; never increases |
| Swift Sneak III enchanted book | 5% | Fixed; never increases |

Each successful roll awards one item, and multiple rewards can drop from the same kill. Elytra and Dragon Head reset to their configured base chances on success. Rewards spawn in owner-only chests in The End and expire after the configured claim time.

Discord-MC-Chat support is optional. If DMCC is installed, reward messages are sent as embeds in the same channel DMCC already uses for Minecraft chat. Both the stable 2.x mod id (`discord-mc-chat`) and the v3 beta mod id (`discord_mc_chat`) are supported.

## Commands

All commands require an operator or server console. `/dr` is a short alias for `/dragonrewards`. Run `/dr` for help, or a group such as `/dr reward` for its commands. Reward names support tab completion: `elytra`, `dragon_head`, `egap`, `swift_sneak`.

Command percentages use **0-100** (for example, `30` means 30%). Configuration changes save automatically to `config/dragonrewards.json` and take effect immediately for future rolls. Already queued rewards keep their contents.

```text
/dr status
/dr reload
/dr reward <reward>
/dr reward <reward> chance <0-100>
/dr reward <reward> enabled <true|false>
/dr reward <elytra|dragon_head> reset
/dr reward swift_sneak level <1-3>

/dr chest list
/dr chest clear
/dr chest remove <x> <y> <z>
/dr chest spawn <player> <reward|all>
/dr chest time <minutes>
/dr chest delay <seconds>
```

Examples:

```text
/dr reward egap chance 30
/dr reward swift_sneak chance 5
/dr reward swift_sneak level 3
/dr chest spawn Steve swift_sneak
```

For Elytra and Dragon Head, `chance` sets the saved base and resets the current pity chance to it; the cap is raised if needed. `reset` clears accumulated pity back to the configured base. The two fixed rewards always use their configured chance, with no pity increase or reset.

Manual chest spawning guarantees the selected reward (or one of each with `all`), even if disabled, without changing drop chances. Chest coordinates are in The End. Claim time affects new chests; spawn delay affects future kills.

The command tree contains only everyday controls. Settings save automatically; manual save, simulation, debug, processed-cache and chest-cleanup commands have been removed. Automatic chest reconciliation still runs normally. To enable logging, edit `debugMode` in the config and use `/dr reload`.

When updating scripts: `feature elytra true` becomes `reward elytra enabled true`; `chests time 30` or `config claim-time 30` becomes `chest time 30`; `config spawn-delay 8` becomes `chest delay 8`; `config reload` becomes `reload`. The old temporary `chance set`/`add` controls are replaced by the saved `reward <reward> chance <percent>` setting; convert fractions to percentages.

## Configuration

Existing config files receive the new fields automatically while keeping existing settings:

```json
{
  "enableEgapDrops": true,
  "enableSwiftSneakDrops": true,
  "egapChance": 0.30,
  "swiftSneakChance": 0.05,
  "swiftSneakLevel": 3
}
```

These are additions to the full config, not a replacement file. JSON chances use **0-1**, unlike command percentages. After editing the file, run `/dr reload`. Chances are clamped to 0-1 and Swift Sneak levels to 1-3.

`messages.rewardsDropped` controls every successful reward announcement. It supports `{player}` and `{rewards}` (the full list of drops), including Elytra-only, Dragon Head-only and any combination of rewards. For example: `Steve killed the Ender Dragon and received: Elytra, Dragon Head.` The legacy `onlyElytra`, `onlyDragonHead` and `bothDropped` templates are preserved in existing configs but no longer used. `messages.nothingDropped` still controls kills without rewards. Discord embeds include all dropped rewards.

## Building and verification

Requires Java 25. Run `./gradlew build` (Windows: `./gradlew.bat build`). The build includes `rewardChecks`, which verifies fixed drop chances, existing pity behavior, config upgrades and persistence, command parsing and autosave, and enchanted-book serialization through pending spawns and reward chests. Complete saved-state checks also cover pity chances, processed dragon IDs, reward ownership and timers. The mod JAR is written to `build/libs/dragonrewards-26.3.0.jar`.

The 26.3 port uses the updated permanent-invulnerability API for chest name markers and retains the existing config and reward-state format. Its build tooling follows the [Fabric 26.3 migration guidance](https://www.fabricmc.net/2026/09/15/263.html).

## Publishing to Modrinth

The `Modrinth Publish` GitHub Actions workflow builds the mod, runs the reward checks, and uploads the regular JAR to Modrinth with Fabric API marked as required. Configure the repository Actions secret `MODRINTH_TOKEN` with a Modrinth token that has Create versions permission, and the repository variable `MODRINTH_ID` with `5YURhtLb`.

Run it from **Actions → Modrinth Publish → Run workflow**, supplying release notes, or publish a GitHub release with a tag matching `mod_version` in `gradle.properties` (an optional `v` prefix is accepted). GitHub release notes become the Modrinth changelog. Use only one publishing method for each version to avoid uploading it twice. Ordinary commits and pushes do not publish a version.

## Updating and diagnosing missing rewards

Stop the server before replacing the mod JAR, keep only one Dragon Rewards JAR in `mods`, then start the server again. `/dr reload` only reloads configuration; it cannot load a new JAR.

Back up the world and `config/dragonrewards.json` before upgrading Minecraft. Update Fabric Loader and Fabric API along with the mod. In a test copy of your server, verify a dragon kill, an owner-only reward claim, and a restart with pending or unclaimed rewards before deploying to your live world. Optional Discord-MC-Chat integration also needs verification with your installed DMCC version.

Version 26.2.1 adds a dragon death-animation fallback alongside the generic death event, tracks attackers through dragon-part hits, and only marks a kill processed after reward preparation succeeds. Both death paths share the same duplicate-kill protection. Previously processed kills are not replayed on upgrade.

The console logs `Processed dragon ...` for each processed kill, including no-drop outcomes, and `Spawned reward chest ...` with the location when a chest appears. `/dr status` shows enabled rewards, chances, and active/pending chest counts. If a new kill produces no chat message, include those console lines and `/dr status` when reporting the problem.
