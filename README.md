# Manhunts Reinvented

Simple server-side Fabric mod for running a manhunt in Minecraft. Supports classic and impostor modes.

### Classic Mode
This mode relies on pre-assigned roles for hunters and speedrunners. The hunters are given compasses to track the speedrunner(s). The target can be changed with `/manhunt track`.

The speedrunners win if the ender dragon is slain, and the hunters win if all the speedrunners are killed. In this mode, when a speedrunner dies, they are put in spectator mode and are out of the game.

### Impostor Mode
This mode revolves around secrecy. The impostor(s) are randomly chosen from the players in the game, and are secretly given a compass and told their role upon the start of the manhunt. The impostors' goal is to stop the speedrunners from beating the game.

Proximity voice chat is encouraged for this mode to enhance the experience. Furthermore in impostor mode, things like achievements and join/leave messages for dead players are disabled so it isn't known if they are alive or not by the others. See below for details and config.

### Infection Mode
This mode is similar to the impostor mode, with one change: speedrunners, upon death, also become hunters. This means that as the game progresses, it will become harder for speedrunners to win with an increasing number of secret hunters in the game. Once again, this mode is best played with proximity voice chat.

## Usage

`/manhunt` is the main command.

`/manhunt team` edits the team. Used for classic manhunts.
- `/manhunt team <player> hunter` adds the player to the hunters.
- `/manhunt team <player> speedrunner` adds the player to the speedrunners.

`/manhunt track <player>` sets the compass to track the player.

`/manhunt start` starts the manhunt. Requires op.
- `/manhunt start classic` starts the manhunt in classic mode.
- `/manhunt start impostor <number>` starts the manhunt and randomly assigns a secret hunter. An optional number can be added to specify a number of hunters; default is 1.

`/manhunt stop` stops the current manhunt. Requires op.

`/manhunt reset-timer` resets all timers (useful after a server crash). Requires op.

`/manhunt alert` sends an alert with coordinates and current dimension.

`/manhunt lost` sends a request for coordinates to other players.

`/manhunt chat <message>` allows players to communicate with their teammates during a manhunt. Speedrunners cannot use this during impostor or infection games.

`/manhunt reveal` can be used to get another player's exact coordinates and dimension. However, each player only gets one per game (by default). This amount can be changed in the config.

## Config File

You can config the time before the release of the hunters and the time between two compass' updates by modifying the config
file `config/manhunt.json`.

The default config file is:

```json
{
  "secondsBeforeRelease": 30,
  "updateCompassEach": 5,
  "removePiglinBrutes": false,
  "disableImpostorGameChat": true,
  "disableMessaging": true,
  "maximumReveals": 1,
  "hunterChat": true,
  "speedrunnerChat": true,
  "enableWorldBorder": true,
  "enableOnePlayerSleeping": true,
}
```

- `secondsBeforeRelease` is the time before the release of the hunters
- `updateCompassEach` is the time between two compass' updates (fewer means more updates)
- `removePiglinBrutes` allows the disabling of piglin brute spawning, instead spawning regular piglins in their place.
- `disableImpostorGameChat` disables the regular game chat during impostor manhunts.
- `disableMessaging` disables /msg and related messaging commands globally. Requires game restart.
- `maximumReveals` is the amount of uses of `/manhunt reveal` that each player can use per game.
- `hunterChat` is whether the hunters can use `/manhunt chat` to communicate with each other.
- `speedrunnerChat` is whether speedrunners can use `/manhunt chat` to communicate with each other in non-impostor/infection modes.
- `enableWorldBorder` is whether the game automatically creates a world border (of 4000 blocks in each direction) in the overworld. This is done to prevent runners from running too far away.
- `enableOnePlayerSleeping` is an option to automatically turn on the gamerule for one player sleeping through the night.

Warning: When you break a lodestone, the compass is still focused on that lodestone!

## Technologies
Based on https://github.com/anhgelus/manhunt-mod

- Fabric
- Fabric API
- Yarn Mappings
- MidnightLib (embedded in jar)

