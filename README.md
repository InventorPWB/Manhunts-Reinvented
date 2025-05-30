# Manhunts Reinvented

Simple server-side Fabric mod creating a manhunt in Minecraft.

Based on https://github.com/anhgelus/manhunt-mod

Made for Minecraft 1.21.

:warning: When you break a lodestone, the compass is still focused on this lodestone!

## Usage

`/manhunt` is the main command.

`/manhunt team` edits the team. Used for classic manhunts.
- `/manhunt team <player> hunter` adds the player to the hunters.
- `/manhunt team <player> speedrunner` adds the player to the speedrunners.

`/manhunt track <player>` sets the compass to track the player.

`/manhunt start` starts the manhunt.
- `/manhunt start classic` starts the manhunt in classic mode.
- `/manhunt start impostor` starts the manhunt and randomly assigns a secret hunter. A number can be added to specify a number of hunters.

`/manhunt stop` stops the current manhunt.

`/manhunt reset-timer` resets all timers (useful after a server crash).

## Config File

You can config the time before the release of the hunters and the time between two compass' updates by modifying the config 
file `config/manhunt.json`.

The default config file is:
```json
{
  "secondsBeforeRelease": 30,
  "updateCompassEach": 5,
  "removePiglinBrutes": false
}
```

- `secondsBeforeRelease` is the time before the release of the hunters
- `updateCompassEach` is the time between two compass' updates (fewer means more updates)
- `removePiglinBrutes` allows the disabling of piglin brute spawning, instead spawning regular piglins in their place.

## Technologies

- Fabric
- Fabric API
- Yarn Mappings
- MidnightLib (embedded in jar)
