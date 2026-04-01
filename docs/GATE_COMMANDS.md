# Gate Commands

This document lists the gate-related admin and testing commands implemented in the addon.

## Root Command

- `/gate`
- Aliases: `g`, `elgate`, `gatetype`, `gatetypes`

Subcommands:
- `/gate list`
- `/gate track <id|clear>`
- `/gate dungeon ...`
- `/gate wave ...`

### Live Gate Tracker

- `/gate list`
- Lists every active live entry in one combined view.
- Each row has a unique tracker ID plus type (`Dungeon`, `Outbreak`, `Hybrid`), rank, coordinates, world, and live status.

- `/gate track <id>`
- Opens the gate tracker HUD for the selected live entry.
- The HUD shows the tracked gate name, type/rank/status, gate coordinates, world, and your own coordinates.
- Refresh cadence is once every 5 game ticks.

- `/gate track clear`
- Closes the tracker HUD and clears the tracked entry.

## Dungeon Gate Commands

- `/gate dungeon`
- Aliases: `dungeons`, `dungeongate`, `dungeongates`

### Spawn Dungeon Gates

- `/gate dungeon spawn <S|A|B|C|D|E|random>`
- Aliases for `spawn`: `gatespawn`, `elgatespawn`, `spawnrandomgate`, `spawndungeongate`
- Spawns a dungeon gate near the player.
- Ranked and random spawns use normal spawn logic, so linked wave combo chance can roll naturally.

Examples:
- `/gate dungeon spawn D`
- `/gate dungeon spawn random`

### Give Dungeon Portal Items

- `/gate dungeon give <d1|d2|d3|swamp|frozen|void|all> <E|D|C|B|A|S>`
- Alias for `give`: `portal`

Portal keys:
- `d1`: Major Dungeon I
- `d2`: Major Dungeon II
- `d3`: Major Dungeon III
- `swamp`: Endgame Swamp Dungeon
- `frozen`: Endgame Frozen Dungeon
- `void`: Endgame Void Golem Dungeon
- `all`: all supported portal items for the chosen rank

Examples:
- `/gate dungeon give d1 E`
- `/gate dungeon give frozen S`
- `/gate dungeon give all D`

### Dungeon Gate Blocks

- `/gate dungeon blocks <list|remove-nearest|remove-all>`
- Aliases for `blocks`: `portalblocks`, `portalblock`, `elportalblocks`, `gateblocks`

Subcommands:
- `list`: list loaded dungeon gate portal blocks
- `remove-nearest`: remove the nearest placed dungeon gate block
- Aliases: `remove`, `rm`
- `remove-all`: remove all placed dungeon gate blocks in the current world
- Aliases: `clear`, `purge`

Examples:
- `/gate dungeon blocks list`
- `/gate dungeon blocks remove-nearest`
- `/gate dungeon blocks remove-all`

### Return Portal Position Lookup

- `/gate dungeon returnpos`
- Aliases: `portalreturnpos`, `returnportalpos`, `rportalpos`
- Lists loaded return portal coordinates in the current world.

### Persisted Gate Instance Entries

- `/gate dungeon instances <list|delete <id>>`
- Aliases for `instances`: `instance`, `gates`, `gateinstances`, `dungeongateinstances`

Subcommands:
- `list`
- Alias: `ls`
- `delete <id>`
- Aliases: `remove`, `del`, `rm`

Examples:
- `/gate dungeon instances list`
- `/gate dungeon instances delete 4`

### Tracked Activity

- `/gate dungeon track [id|clear]`
- Aliases: `tracks`, `tracking`, `listtrack`, `trackgatetypes`
- Legacy access path for the live tracker commands.
- With no argument it shows the same combined list as `/gate list`.
- With an ID it behaves like `/gate track <id>`.

### Clear All Tracked Dungeon Instances

- `/gate dungeon deleteinstances`
- Aliases: `delete`, `clear`, `clearinstances`, `elcleardungeons`, `cleareldungeons`
- Requests removal of all tracked Endless Leveling dungeon instances.

### Dungeon Gate Debug

- `/gate dungeon debug prevententer <true|false|status>`

Debug tree:
- `/gate dungeon debug`
- `/gate dungeon debug prevententer`
- `/gate dungeon debug prevententer true`
- `/gate dungeon debug prevententer false`
- `/gate dungeon debug prevententer status`

## Wave Gate Commands

- `/gate wave`
- Aliases: `waves`, `wavegate`, `wavegates`, `outbreak`, `outbreaks`

### Natural Wave Gate Countdown

- `/gate wave <S|A|B|C|D|E|random>`
- Schedules a natural wave gate countdown near the player.

Examples:
- `/gate wave random`
- `/gate wave B`

### Explicit Wave Portal Spawn

- `/gate wave spawn <rank>`
- Aliases: `schedule`, `preview`
- Spawns a wave portal and respects the normal mob-start countdown.

### Immediate Wave Test

- `/gate wave test <rank>`
- Starts a wave immediately without a countdown.

### Forced Dungeon+Wave Combo Test

- `/gate wave testcombo <rank>`
- Aliases: `combo`, `testgatewave`
- Spawns a dungeon gate forcibly linked to a wave gate countdown.

### Clear Linked Combo State

- `/gate wave clearcombo`
- Aliases: `clearlinked`, `comboff`, `unlockgatewave`
- Clears linked dungeon gate + wave gate countdowns and locks for the player.

### Start / Stop / Status / Skip

- `/gate wave start <rank>`
- Start a wave sequence immediately.

- `/gate wave stop`
- Alias: `end`
- Stop an active wave sequence.

- `/gate wave status`
- Alias: `info`
- Show active wave status.

- `/gate wave skip`
- Alias: `next`
- Kill current wave mobs and advance to the next wave.

### Clear Wave Portal Visuals

- `/gate wave clearparticles`
- Aliases: `purgeparticles`, `cleanparticles`, `clearwaveportals`
- Clears stuck wave magic-portal anchor visuals in the current world.
