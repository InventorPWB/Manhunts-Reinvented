package world.inventorpwb.manhunt;

import java.util.*;
import java.util.stream.Collectors;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameRules;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import eu.midnightdust.lib.config.MidnightConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.command.EntitySelector;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.LodestoneTrackerComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.PiglinBruteEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.GameMode;

public final class Manhunt implements ModInitializer {
	public static final String MOD_ID = "manhunt";
	public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

	public static Manhunt INSTANCE;

	private final Set<UUID> hunters = new HashSet<>();
	private final Set<UUID> speedrunners = new HashSet<>();
	private final Map<UUID, UUID> trackedMap = new HashMap<>();

	private final Timer timer = new Timer();

	private enum State {
		ON, OFF
	}

	private enum GameModeType {
		CLASSIC,
		IMPOSTOR
	}

	private State state = State.OFF;

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Manhunt");
		MidnightConfig.init(MOD_ID, Config.class);
		LOGGER.info(Config.secondsBeforeRelease);
		LOGGER.info(Config.updateCompassEach);

		INSTANCE = this;

		final LiteralArgumentBuilder<ServerCommandSource> command = literal("manhunt");

		final LiteralArgumentBuilder<ServerCommandSource> track = literal("track");
		track.then(RequiredArgumentBuilder.<ServerCommandSource, EntitySelector>argument("player", EntityArgumentType.player())
			.executes(context -> {
				final var source = context.getSource();
				final var tracked = (ServerPlayerEntity) EntityArgumentType.getEntity(context, "player");
				assert tracked.getDisplayName() != null;
				final var player = source.getPlayer();
				if (player == null) return 2;
				final var uuid = player.getUuid();
				if (trackedMap.get(uuid) != null && trackedMap.get(uuid) == tracked.getUuid()) {
					source.sendFeedback(() -> Text.literal("Already tracking "+tracked.getDisplayName().getString()), false);
					return Command.SINGLE_SUCCESS;
				}
				trackedMap.put(player.getUuid(), tracked.getUuid());
				updateCompass(player, tracked);
				source.sendFeedback(() -> Text.literal("Tracking "+tracked.getDisplayName().getString()), false);
				return Command.SINGLE_SUCCESS;
			})
		);
		final LiteralArgumentBuilder<ServerCommandSource> team = literal("team");
		final RequiredArgumentBuilder<ServerCommandSource, EntitySelector> teamP = RequiredArgumentBuilder.argument("player", EntityArgumentType.player());
		teamP.then(LiteralArgumentBuilder.<ServerCommandSource>literal("hunter").executes(context -> {
			final var p = (ServerPlayerEntity) EntityArgumentType.getEntity(context, "player");
				speedrunners.remove(p.getUuid());
			hunters.add(p.getUuid());
			assert p.getDisplayName() != null;
			context.getSource().sendFeedback(() -> Text.literal(p.getDisplayName().getString()+" added to hunter"), true);
			return Command.SINGLE_SUCCESS;
		}))
		.then(LiteralArgumentBuilder.<ServerCommandSource>literal("speedrunner").executes(context -> {
			final var p = (ServerPlayerEntity) EntityArgumentType.getEntity(context, "player");
			hunters.remove(p.getUuid());
			speedrunners.add(p.getUuid());
			assert p.getDisplayName() != null;
			context.getSource().sendFeedback(() -> Text.literal(p.getDisplayName().getString()+" added to speedrunner"), true);
			return Command.SINGLE_SUCCESS;
		}));
		team.then(teamP);
		final LiteralArgumentBuilder<ServerCommandSource> start = literal("start");
		start.requires(source -> source.hasPermissionLevel(2));

		// "classic" mode
		start.then(LiteralArgumentBuilder.<ServerCommandSource>literal("classic")
			.executes((CommandContext<ServerCommandSource> context) ->
				startGame(context, GameModeType.CLASSIC, 0)
			)
		);

		// impostor subcommand with optional count
		start.then(CommandManager.literal("impostor")
				// default count = 1
				.executes(ctx -> startGame(ctx, GameModeType.IMPOSTOR, 1))
				// optional integer argument
				.then(CommandManager.argument("count", IntegerArgumentType.integer(1))
						.executes(ctx -> {
							int count = IntegerArgumentType.getInteger(ctx, "count");

							return startGame(ctx, GameModeType.IMPOSTOR, count);
						})
				)
		);

		final LiteralArgumentBuilder<ServerCommandSource> stop = literal("stop");
		stop.requires(source -> source.hasPermissionLevel(2));
		stop.executes((CommandContext<ServerCommandSource> context) -> {
			state = State.OFF;
			speedrunners.clear();
			hunters.clear();
			trackedMap.clear();

			// Show death messages
			context.getSource().getServer().getGameRules().get(GameRules.SHOW_DEATH_MESSAGES).set(true, context.getSource().getServer());

			final PlayerManager pm = context.getSource().getServer().getPlayerManager();

			for (final UUID hunter : hunters) {
				final ServerPlayerEntity player = pm.getPlayer(hunter);
				if (player != null) {
					// Remove all compasses from the inventory
					player.getInventory().remove(
							stack -> stack.getItem() == Items.COMPASS,  // Predicate<ItemStack>
							Integer.MAX_VALUE,                          // Max count to remove
							player.getInventory()                       // Inventory context (usually itself)
					);
					player.sendMessage(Text.literal("Your tracking compass has been removed.").formatted(Formatting.GRAY), false);
				}
			}

			hunters.clear();
			trackedMap.clear();

			context.getSource().sendFeedback(() -> Text.literal("Manhunt stopped!"), true);
			return Command.SINGLE_SUCCESS;
		});

		final LiteralArgumentBuilder<ServerCommandSource> resetTimer = literal("reset-timer");
		resetTimer.requires(source -> source.hasPermissionLevel(2));
		resetTimer.executes(context -> {
			state = State.ON;
			setTimer(context.getSource().getServer().getPlayerManager());
			context.getSource().sendFeedback(() -> Text.literal("Timer reset"), true);
			return Command.SINGLE_SUCCESS;
		});

		command.then(track);
		command.then(team);
		command.then(start);
		command.then(resetTimer);
		command.then(stop);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(command));

		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (state == State.OFF) return;
			final var uuid = oldPlayer.getUuid();
			if (hunters.contains(uuid)) {
				newPlayer.giveItemStack(new ItemStack(Items.COMPASS));
				return;
			}
			speedrunners.remove(uuid);
			newPlayer.changeGameMode(GameMode.SPECTATOR);
			if (!speedrunners.isEmpty()) return;

			// No speedrunners left → hunters win!
			MinecraftServer server = newPlayer.server;
			server.getPlayerManager().broadcast(
					Text.literal("§cHunters win! All speedrunners have died!").formatted(Formatting.RED),
					false
			);

			for (final ServerPlayerEntity player : newPlayer.server.getPlayerManager().getPlayerList()) {
				player.changeGameMode(GameMode.SPECTATOR);
				hunters.remove(player.getUuid());
				speedrunners.remove(player.getUuid());
			}

			state = State.OFF;
			timer.cancel();
		});

		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (!(entity instanceof PiglinBruteEntity)) return;
			if (!Config.removePiglinBrutes) return;

			EntityType.PIGLIN.spawn(world, entity.getBlockPos(), SpawnReason.MOB_SUMMONED);
			entity.discard();
		});
	}

	/**
	 * Called from Mixin when the Ender Dragon is killed.
	 */
	public static void onEnderDragonDeath(MinecraftServer server) {
		server.getPlayerManager().broadcast(
				Text.literal("§aSpeedrunners win! The Ender Dragon has been slain!"),
				false
		);
		// Cleanup
		INSTANCE.state = State.OFF;
		INSTANCE.hunters.clear();
		INSTANCE.speedrunners.clear();
		INSTANCE.trackedMap.clear();
		INSTANCE.timer.cancel();
	}

	private void setTimer(PlayerManager pm) {
		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				for (final UUID uuid : hunters) {
					final ServerPlayerEntity hunter = pm.getPlayer(uuid);
					if (hunter == null) continue;
					final ServerPlayerEntity tracked = pm.getPlayer(trackedMap.get(uuid));
					if (tracked == null) continue;
					updateCompass(hunter, tracked);
				}
			}
		}, Config.secondsBeforeRelease*1000L, Config.updateCompassEach*1000L);
	}

	private void updateCompass(ServerPlayerEntity player, ServerPlayerEntity tracked) {
		final var trackerComponent = new LodestoneTrackerComponent(Optional.of(GlobalPos.create(tracked.getWorld().getRegistryKey(), tracked.getBlockPos())), true);

		ItemStack is = null;
		int slot = PlayerInventory.NOT_FOUND;
		final var inv = player.getInventory();
		if (inv.getMainHandStack().isOf(Items.COMPASS)) {
			is = inv.getMainHandStack();
			slot = inv.getSlotWithStack(is);
		} else if (inv.getStack(PlayerInventory.OFF_HAND_SLOT).isOf(Items.COMPASS)) {
			is = inv.getStack(PlayerInventory.OFF_HAND_SLOT);
			slot = PlayerInventory.OFF_HAND_SLOT;
		} else {
			for (int i = 0; i < PlayerInventory.MAIN_SIZE && is == null; i++) {
				final var stack = inv.getStack(i);
				if (stack.isOf(Items.COMPASS)) {
					is = stack;
					slot = i;
				}
			}
		}
		if (is == null) {
			LOGGER.warn("Compass item is null");
			is = new ItemStack(Items.COMPASS);
		}
		is.set(DataComponentTypes.LODESTONE_TRACKER, trackerComponent);
		if (slot == PlayerInventory.NOT_FOUND) {
			player.giveItemStack(is);
			return;
		}
		inv.setStack(slot, is);
	}

	private int startGame(CommandContext<ServerCommandSource> context, GameModeType mode, int impostors) {
		if (state == State.ON) {
			context.getSource().sendFeedback(() -> Text.literal("Cannot start a manhunt if one is already started!"), false);
			return -1;
		}

		final PlayerManager pm = context.getSource().getServer().getPlayerManager();
		final var players = pm.getPlayerList();

		for (final ServerPlayerEntity player : players) {
			player.setHealth(player.getMaxHealth());
			player.setExperienceLevel(0);
			player.getInventory().clear();
			player.getHungerManager().eat(
					new FoodComponent(20, 20.0f, true)
			);
			final var spawn = player.getServerWorld().getSpawnPos();
			player.teleport(spawn.getX(), spawn.getY(), spawn.getZ(), false);
		}

		// Assign roles for impostor mode
		if (mode == GameModeType.IMPOSTOR) {
			hunters.clear();
			speedrunners.clear();

			context.getSource().getServer().getGameRules().get(GameRules.SHOW_DEATH_MESSAGES).set(false, context.getSource().getServer());

			if (players.size() < 2) {
				context.getSource().sendFeedback(() -> Text.literal("At least 2 players are required for impostor mode."), false);
				return Command.SINGLE_SUCCESS;
			}

			while (hunters.size() < impostors) {
				final var randomIndex = (int) (Math.random() * players.size());
				final var chosenHunter = players.get(randomIndex);
				if (hunters.contains(chosenHunter.getUuid())) continue;

				hunters.add(chosenHunter.getUuid());
				chosenHunter.sendMessage(Text.literal("You are the hunter! Act like a speedrunner, but try to stop the others!").formatted(Formatting.DARK_RED), false);
			}

            for (ServerPlayerEntity player : players) {
                if (!hunters.contains(player.getUuid())) {
                    speedrunners.add(player.getUuid());
					player.sendMessage(Text.literal("You are a speedrunner! Try to beat the game without being stopped by the hunter(s)!").formatted(Formatting.GREEN));
                }
            }

			// Notify hunters of their teammates if more than one
			if (impostors > 1) {
				List<ServerPlayerEntity> hunterPlayers = players.stream()
						.filter(p -> hunters.contains(p.getUuid()))
						.toList();

				for (ServerPlayerEntity hunter : hunterPlayers) {
					String teammateNames = hunterPlayers.stream()
							.filter(other -> !other.getUuid().equals(hunter.getUuid()))
							.map(ServerPlayerEntity::getName)
							.map(Text::getString)
							.collect(Collectors.joining(", "));

					hunter.sendMessage(Text.literal("Your fellow hunter" + (impostors > 2 ? "s are: " : " is: ") + teammateNames).formatted(Formatting.RED), false);
				}
			}
		}

		// Start all hunters' compasses at a random runner
		Random random = new Random();
		for (UUID hunterUuid : hunters) {
			// Convert set to list to allow index access
			List<UUID> runnerList = new ArrayList<>(speedrunners);

			if (runnerList.isEmpty()) continue; // avoid crash

			UUID runnerUuid = runnerList.get(random.nextInt(runnerList.size()));

			trackedMap.put(hunterUuid, runnerUuid);

			ServerPlayerEntity hunter = pm.getPlayer(hunterUuid);
			ServerPlayerEntity runner = pm.getPlayer(runnerUuid);

			if (hunter != null && runner != null) {
				updateCompass(hunter, runner);
			}
		}

		state = State.ON;
		setTimer(pm);

		// Only classic run logic remains
		if (mode == GameModeType.IMPOSTOR) return Command.SINGLE_SUCCESS;

		for (final UUID uuid : hunters) {
			final var hunter = pm.getPlayer(uuid);
			assert hunter != null;

			hunter.sendMessage(Text.literal("You are the hunter! Try to stop the speedrunners from beating the game!").formatted(Formatting.RED));

			hunter.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, Config.secondsBeforeRelease * 20, 255, false, false));
			var attr = hunter.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED);
			if (attr != null) {
				final var modifier = new EntityAttributeModifier(
						Identifier.of("manhunt.speed"),
						-1,
						EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE
				);
				attr.addTemporaryModifier(modifier);
			}
			attr = hunter.getAttributeInstance(EntityAttributes.GRAVITY);
			if (attr != null) {
				final var modifier = new EntityAttributeModifier(
						Identifier.of("manhunt.gravity"),
						5,
						EntityAttributeModifier.Operation.ADD_VALUE
				);
				attr.addTemporaryModifier(modifier);
			}
			LOGGER.info("Added modifiers to {}", hunter.getDisplayName());
		}
		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				LOGGER.info("Removing modifier to hunters");
				for (final UUID uuid : hunters) {
					final var hunter = pm.getPlayer(uuid);
					assert hunter != null;
					var attr = hunter.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED);
					if (attr != null) {
						attr.removeModifier(Identifier.of("manhunt.speed"));
					}
					attr = hunter.getAttributeInstance(EntityAttributes.GRAVITY);
					if (attr != null) {
						attr.removeModifier(Identifier.of("manhunt.gravity"));
					}
				}
			}
		}, Config.secondsBeforeRelease*1000L);
		context.getSource().sendFeedback(() -> Text.literal("Game started!"), true);
		return Command.SINGLE_SUCCESS;
	}

}
