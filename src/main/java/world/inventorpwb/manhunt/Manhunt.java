package world.inventorpwb.manhunt;

import java.util.*;
import java.util.stream.Collectors;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
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

import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.GameMode;
import org.jetbrains.annotations.NotNull;

public final class Manhunt implements ModInitializer {
	public static final String MOD_ID = "manhunt";
	public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

	public static Manhunt INSTANCE;

	private final Set<UUID> hunters = new HashSet<>();
	private final Set<UUID> speedrunners = new HashSet<>();
	private final Set<Text> deadNames = new HashSet<>();
	private final Map<UUID, UUID> trackedMap = new HashMap<>();
	private final Map<UUID, Integer> usedReveals = new HashMap<>();
	private final Map<UUID, Map<RegistryKey<World>, BlockPos>> lastPlayerPositions = new HashMap<>();

	private Timer timer = new Timer();

	private static int alertPlayer(CommandContext<ServerCommandSource> context) {
		ServerPlayerEntity player = context.getSource().getPlayer();
		assert player != null;

		Vec3d posVec = new Vec3d(player.getX(), player.getY(), player.getZ());
		int x = (int) Math.round(posVec.x);
		int y = (int) Math.round(posVec.y);
		int z = (int) Math.round(posVec.z);

		String playerName = player.getName().getString();
		String dimension = player.getEntityWorld().getRegistryKey().getValue().toString(); // e.g., "minecraft:overworld"
		String formatted = formatDimension(dimension);

		String pos = String.format("(%d, %d, %d)", x, y, z);
		String message = String.format("%s sent an alert at %s in the %s!", playerName, pos, formatted);

		context.getSource().getServer().getPlayerManager().broadcast(
				Text.literal(message),
				false
		);
		return Command.SINGLE_SUCCESS;
	}

	private static int lostPlayer(CommandContext<ServerCommandSource> context) {
		ServerPlayerEntity player = context.getSource().getPlayer();
		assert player != null;

		String playerName = player.getName().getString();
		context.getSource().getServer().getPlayerManager().broadcast(
				Text.literal(playerName + " is lost and needs coordinates!"),
				false // false = broadcast to all players, not ops only
		);
		return Command.SINGLE_SUCCESS;
	}

	private int rolePlayer(CommandContext<ServerCommandSource> context) {
		int result = Command.SINGLE_SUCCESS;
		ServerPlayerEntity player = context.getSource().getPlayer();
		assert player != null;

		if (!INSTANCE.isActive()) {
			context.getSource().getPlayer().sendMessage(Text.literal("There is no active manhunt!"));
		} else {
			boolean isHunter = hunters.contains(player.getUuid());
			context.getSource().getPlayer().sendMessage(Text.literal(String.format("You are a %s", isHunter ? "Hunter!" : "Speedrunner!")));
		}

		return result;
	}

	private int stopManhunt(CommandContext<ServerCommandSource> context) {
		int result = Command.SINGLE_SUCCESS;

		if (state == State.OFF) {
			context.getSource().sendFeedback(() -> Text.literal("There is no active manhunt!"), true);
		} else {
			state = State.OFF;
			speedrunners.clear();
			hunters.clear();
			trackedMap.clear();
			deadNames.clear();// Show death messages
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
		}

		return result;
	}

	private int resetTimer(CommandContext<ServerCommandSource> context) {
		state = State.ON;
		setTimer(context.getSource().getServer().getPlayerManager());
		context.getSource().sendFeedback(() -> Text.literal("Timer reset"), true);
		return Command.SINGLE_SUCCESS;
	}

	private enum State {
		ON, OFF
	}

	public enum GameModeType {
		CLASSIC,
		IMPOSTOR,
		INFECTION
	}

	private State state = State.OFF;
	private GameModeType manhuntMode;

	/** Returns true if a manhunt is currently in progress. */
	public boolean isActive() {
		return INSTANCE != null && INSTANCE.state == State.ON;
	}

	/** Returns true if the given UUID is marked as dead in this Manhunt. */
	public boolean isDead(Text name) {
		return INSTANCE.deadNames.contains(name);
	}

	/** Returns true if the current manhunt is an impostor manhunt **/
	public boolean isModeImpostor() {
		return INSTANCE.manhuntMode == GameModeType.IMPOSTOR;
	}

	/** Returns true if the current manhunt is an infection manhunt **/
	public boolean isModeInfection() {
		return INSTANCE.manhuntMode == GameModeType.INFECTION;
	}

	private static String formatDimension(String dimension) {
		return switch (dimension) {
			case "minecraft:overworld" -> "Overworld";
			case "minecraft:the_nether" -> "Nether";
			case "minecraft:the_end" -> "End";
			default -> dimension;
		};
	}

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Manhunt");
		MidnightConfig.init(MOD_ID, Config.class);
		LOGGER.info(Config.secondsBeforeRelease);
		LOGGER.info(Config.updateCompassEach);

		INSTANCE = this;

		final LiteralArgumentBuilder<ServerCommandSource> command = literal("manhunt");

		final LiteralArgumentBuilder<ServerCommandSource> lost = literal("lost");
		lost.executes(Manhunt::lostPlayer);

		final LiteralArgumentBuilder<ServerCommandSource> alert = literal("alert");
		alert.executes(Manhunt::alertPlayer);

		final LiteralArgumentBuilder<ServerCommandSource> role = literal("role");
		role.executes(INSTANCE::rolePlayer);

		final LiteralArgumentBuilder<ServerCommandSource> reveal = literal("reveal");
		reveal.then(RequiredArgumentBuilder.<ServerCommandSource, EntitySelector>argument("player", EntityArgumentType.player())
				.executes(context -> {
					ServerPlayerEntity caller = context.getSource().getPlayer();
					assert caller != null;

					UUID callerId = caller.getUuid();

					// use 0 if this is the first time
					int usedCount = usedReveals.getOrDefault(callerId, 0);
					if (usedCount >= Config.maximumReveals) {
						context.getSource()
								.sendFeedback(() -> Text.literal("You already used all your reveals!"), false);
						return Command.SINGLE_SUCCESS;
					}

					ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
					Vec3d posVec = new Vec3d(target.getX(), target.getY(), target.getZ());
					int x = (int) Math.round(posVec.x),
							y = (int) Math.round(posVec.y),
							z = (int) Math.round(posVec.z);

					// bump the counter
					usedReveals.put(callerId, usedCount + 1);
					int left = Config.maximumReveals - (usedCount + 1);

					String message = String.format(
							"%s is at (%d, %d, %d) in the %s! You have %d reveals left.",
							target.getName().getString(),
							x, y, z,
							formatDimension(target.getEntityWorld()
									.getRegistryKey()
									.getValue()
									.toString()),
							left
					);

					context.getSource()
							.sendFeedback(() -> Text.literal(message)
											.formatted(Formatting.GOLD),
									false);

					return Command.SINGLE_SUCCESS;
				})
		);

		final LiteralArgumentBuilder<ServerCommandSource> track = literal("track");
		track.then(RequiredArgumentBuilder.<ServerCommandSource, EntitySelector>argument("player", EntityArgumentType.player())
				.executes(context -> {
					final var source = context.getSource();
					final var tracked = (ServerPlayerEntity) EntityArgumentType.getEntity(context, "player");
					assert tracked.getDisplayName() != null;
					final var player = source.getPlayer();
					if (player == null) return 2;
					final var uuid = player.getUuid();

					if (!hunters.contains(uuid)) {
						source.sendFeedback(() -> Text.literal("You aren't a hunter!"), false);
						return Command.SINGLE_SUCCESS;
					}

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
		teamP.then(LiteralArgumentBuilder.<ServerCommandSource>literal("hunter").executes(addPlayerTeam(speedrunners, hunters, " added to hunter")))
				.then(LiteralArgumentBuilder.<ServerCommandSource>literal("speedrunner").executes(addPlayerTeam(hunters, speedrunners, " added to speedrunner")));
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

		// infection subcommand with optional count
		start.then(CommandManager.literal("infection")
				// default count = 1
				.executes(ctx -> startGame(ctx, GameModeType.INFECTION, 1))
				// optional integer argument
				.then(CommandManager.argument("count", IntegerArgumentType.integer(1))
						.executes(ctx -> {
							int count = IntegerArgumentType.getInteger(ctx, "count");

							return startGame(ctx, GameModeType.INFECTION, count);
						})
				)
		);

		final LiteralArgumentBuilder<ServerCommandSource> stop = literal("stop");
		stop.requires(source -> source.hasPermissionLevel(2));
		stop.executes(this::stopManhunt);

		final LiteralArgumentBuilder<ServerCommandSource> resetTimer = literal("reset-timer");
		resetTimer.requires(source -> source.hasPermissionLevel(2));
		resetTimer.executes(this::resetTimer);

		final LiteralArgumentBuilder<ServerCommandSource> chat = literal("chat");
		chat.then(CommandManager.argument("message", StringArgumentType.string())
				.executes(context -> {
					ServerPlayerEntity player = context.getSource().getPlayer();
					assert player != null;

					String messageText = StringArgumentType.getString(context, "message");
					PlayerManager pm = context.getSource().getServer().getPlayerManager();
					boolean isHunter = hunters.contains(player.getUuid());

					// Build the team label
					Text teamLabel = Text.literal("[")
							.append(Text.literal(isHunter ? "Hunters" : "Speedrunners")
									.styled(style -> style.withColor(isHunter ? Formatting.RED : Formatting.GREEN)))
							.append(Text.literal("] "));

					// Username (white)
					Text username = Text.literal(player.getName().getString())
							.styled(style -> style.withColor(Formatting.WHITE));

					// Message (also white, but separate for clarity)
					Text content = Text.literal(": " + messageText)
							.styled(style -> style.withColor(Formatting.WHITE));

					// Final composed message
					Text formatted = Text.empty().append(teamLabel).append(username).append(content);

					if (isHunter && Config.hunterChat) {
						for (UUID hunterUuid : hunters) {
							ServerPlayerEntity hunter = pm.getPlayer(hunterUuid);
							assert hunter != null;

							hunter.sendMessage(formatted);
						}
					} else if (Config.speedrunnerChat && !INSTANCE.isModeImpostor() && !INSTANCE.isModeInfection()) {
						for (UUID hunterUuid : speedrunners) {
							ServerPlayerEntity runner = pm.getPlayer(hunterUuid);
							assert runner != null;

							runner.sendMessage(formatted);
						}
					} else {
						context.getSource().sendFeedback(() -> Text.literal("You cannot send manhunt chat messages!"), false);
						return Command.SINGLE_SUCCESS;
					}

					return Command.SINGLE_SUCCESS;
				})
		);

		command.then(track);
		command.then(team);
		command.then(start);
		command.then(resetTimer);
		command.then(stop);
		command.then(lost);
		command.then(alert);
		command.then(role);
		command.then(reveal);
		command.then(chat);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(command));

		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (state == State.OFF) return;
			final var uuid = oldPlayer.getUuid();
			PlayerManager pm = Objects.requireNonNull(newPlayer.getEntityWorld().getServer()).getPlayerManager();

			// Tell all hunters when someone dies
			for (UUID hunterUuid : hunters) {
				ServerPlayerEntity hunter = pm.getPlayer(hunterUuid);
				assert hunter != null;
				String newPlayerName = newPlayer.getName().getString();
				hunter.sendMessage(Text.literal(String.format("%s has died!" + ((manhuntMode == GameModeType.INFECTION && !hunters.contains(uuid)) ? " They are now a hunter." : ""), newPlayerName))
						.formatted(Formatting.DARK_RED));
			}

			// Give deceased hunter a new compass
			if (hunters.contains(uuid)) {
				newPlayer.giveItemStack(new ItemStack(Items.COMPASS));
				randomizeHunterTracking(uuid, pm, new Random());
				return;
			}

			// If player was not a hunter already, continue
			speedrunners.remove(uuid);

			if (manhuntMode == GameModeType.INFECTION) {
				hunters.add(uuid);

				ServerPlayerEntity player = pm.getPlayer(uuid);
				assert player != null;

				player.sendMessage(Text.literal("You died! You are now a hunter. Try to stop the remaining speedrunners!").formatted(Formatting.DARK_RED));

				final var players = pm.getPlayerList();
				List<ServerPlayerEntity> hunterPlayers = players.stream()
						.filter(p -> hunters.contains(p.getUuid()))
						.toList();

				String teammateNames = hunterPlayers.stream()
						.filter(other -> !other.getUuid().equals(newPlayer.getUuid()))
						.map(ServerPlayerEntity::getName)
						.map(Text::getString)
						.collect(Collectors.joining(", "));

				newPlayer.sendMessage(Text.literal("Your fellow hunter" + (teammateNames.length() > 1 ? "s are: " : " is: ") + teammateNames).formatted(Formatting.RED), false);

				newPlayer.giveItemStack(new ItemStack(Items.COMPASS));

				Random random = new Random();
				randomizeHunterTracking(newPlayer.getUuid(), pm, random);
			} else {
				deadNames.add(oldPlayer.getName());
				newPlayer.changeGameMode(GameMode.SPECTATOR);
			}

			if (!speedrunners.isEmpty()) return;

			// No speedrunners left → hunters win!
			MinecraftServer server = Objects.requireNonNull(newPlayer.getEntityWorld().getServer());
			server.getPlayerManager().broadcast(
					Text.literal("§cHunters win! All speedrunners have died!").formatted(Formatting.RED),
					false
			);

			for (final ServerPlayerEntity player : Objects.requireNonNull(newPlayer.getEntityWorld().getServer()).getPlayerManager().getPlayerList()) {
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

	private @NotNull Command<ServerCommandSource> addPlayerTeam(Set<UUID> speedrunners, Set<UUID> hunters, String x) {
		return context -> {
			final var p = (ServerPlayerEntity) EntityArgumentType.getEntity(context, "player");
			speedrunners.remove(p.getUuid());
			hunters.add(p.getUuid());
			assert p.getDisplayName() != null;
			context.getSource().sendFeedback(() -> Text.literal(p.getDisplayName().getString() + x), true);
			return Command.SINGLE_SUCCESS;
		};
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
					if (tracked.getEntityWorld() != hunter.getEntityWorld()) continue;
					updateCompass(hunter, tracked);
				}
			}
		}, Config.secondsBeforeRelease*1000L, Config.updateCompassEach*1000L);
	}

	private void updateCompass(ServerPlayerEntity player, ServerPlayerEntity tracked) {
		// Get the world (dimension) keys of the tracked player and hunter player
		RegistryKey<World> trackedDimensionKey = tracked.getEntityWorld().getRegistryKey();
		RegistryKey<World> hunterDimensionKey = player.getEntityWorld().getRegistryKey();

		// Get the tracked player's block position
		BlockPos trackedBlockPos = tracked.getBlockPos();

		// Assign the tracked player's current position in whatever dimension they are in
		Map<RegistryKey<World>, BlockPos> trackedPositions =
				lastPlayerPositions.computeIfAbsent(tracked.getUuid(), k -> new HashMap<>());
		trackedPositions.put(trackedDimensionKey, trackedBlockPos);

		// Add the tracked player's last position in the hunter's dimension onto a tracker component
		final var trackerComponent = new LodestoneTrackerComponent(Optional.of(GlobalPos.create(hunterDimensionKey, lastPlayerPositions.get(tracked.getUuid()).get(hunterDimensionKey))), true);

		ItemStack is = null;
		int slot = PlayerInventory.NOT_FOUND;
		final var inv = player.getInventory();
		if (player.getMainHandStack().isOf(Items.COMPASS)) {
			is = player.getMainHandStack();
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

		// Apply the tracker component
		is.set(DataComponentTypes.LODESTONE_TRACKER, trackerComponent);

		// Set the custom name for tracking
		is.set(
				DataComponentTypes.CUSTOM_NAME,
				Text.literal("Tracking Player: ").append(tracked.getDisplayName())
		);

		// Apply Curse of Vanishing to the compass
		// These 4 lines took way too long to figure out
		Registry<Enchantment> enchantmentRegistry = Objects.requireNonNull(Objects.requireNonNull(player.getEntityWorld().getServer())).getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
		RegistryKey<Enchantment> vanishingCurseKey = Enchantments.VANISHING_CURSE;
		RegistryEntry<Enchantment> vanishingCurseEntry = enchantmentRegistry.getOrThrow(vanishingCurseKey);
		is.addEnchantment(vanishingCurseEntry, 1);

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

		manhuntMode = mode;

		// 1) If there’s already a Timer with pending tasks, cancel it:
		timer.cancel();
		// 2) Create a new Timer so no old tasks remain:
		timer = new Timer();

		MinecraftServer server = context.getSource().getServer();
		final PlayerManager pm = server.getPlayerManager();
		final var players = pm.getPlayerList();

		for (final ServerPlayerEntity player : players) {
			player.setHealth(player.getMaxHealth());
			player.setExperienceLevel(0);
			player.getInventory().clear();
			player.getHungerManager().eat(
					new FoodComponent(20, 20.0f, true)
			);
			final BlockPos spawn = player.getEntityWorld().getSpawnPoint().getPos();
			player.teleport(spawn.getX(), spawn.getY(), spawn.getZ(), false);
		}

		// Enable one player sleeping through gamerule depending on config
		if (Config.enableOnePlayerSleeping) {
			server.getGameRules().get(GameRules.PLAYERS_SLEEPING_PERCENTAGE).set(0, server);
		}

		if (Config.enableWorldBorder) {
			// Get the overworld
			ServerWorld overworld = server.getWorld(World.OVERWORLD);
			if (overworld == null) {
				context.getSource().sendError(Text.literal("Overworld not found!"));
				return 0;
			}

			WorldBorder border = overworld.getWorldBorder();

			// Set world border center
			border.setCenter(0.0, 0.0);

			// Set the border size (full diameter in blocks)
			border.setSize(8000.0);
		}

		// Assign roles for impostor mode
		if (mode == GameModeType.IMPOSTOR || mode == GameModeType.INFECTION) {
			hunters.clear();
			speedrunners.clear();

			server.getGameRules().get(GameRules.SHOW_DEATH_MESSAGES).set(false, server);

			if (players.size() < 3) {
				context.getSource().sendFeedback(() -> Text.literal("At least 3 players are required for impostor/infection mode."), false);
				return Command.SINGLE_SUCCESS;
			}

			while (hunters.size() < impostors) {
				final var randomIndex = (int) (Math.random() * players.size());
				final var chosenHunter = players.get(randomIndex);
				if (hunters.contains(chosenHunter.getUuid())) continue;

				hunters.add(chosenHunter.getUuid());
				chosenHunter.sendMessage(Text.literal("You are " + (impostors > 1 ? "a" : "the") + " hunter! Act like a speedrunner, but try to stop the others!").formatted(Formatting.DARK_RED), false);
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
			randomizeHunterTracking(hunterUuid, pm, random);
		}

		state = State.ON;
		setTimer(pm);

		// schedule a tip 10 seconds later
		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				// broadcast to each hunter:
				for (UUID hunterUuid : hunters) {
					ServerPlayerEntity hunter = pm.getPlayer(hunterUuid);
					if (hunter != null) {
						hunter.sendMessage(
								Text.literal("Tip: Use /manhunt track <player> to track a specific player!")
										.formatted(Formatting.GRAY),
								false
						);
					}
				}
			}
		}, 10000L); // 10_000 ms = 10 seconds

		// Only classic run logic remains
		if (mode == GameModeType.IMPOSTOR || mode == GameModeType.INFECTION) return Command.SINGLE_SUCCESS;

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

	private void randomizeHunterTracking(UUID hunterUuid, PlayerManager pm, Random random) {
		// Convert set to list to allow index access
		List<UUID> runnerList = new ArrayList<>(speedrunners);

		if (runnerList.isEmpty()) return;

		UUID runnerUuid = runnerList.get(random.nextInt(runnerList.size()));

		trackedMap.put(hunterUuid, runnerUuid);

		ServerPlayerEntity hunter = pm.getPlayer(hunterUuid);
		ServerPlayerEntity runner = pm.getPlayer(runnerUuid);

		if (hunter != null && runner != null) {
			PlayerInventory inv = hunter.getInventory();
			ItemStack compass = new ItemStack(Items.COMPASS);
			// Try to find the first empty main inventory slot (slots 9–35)
			for (int slot = 9; slot < inv.size(); slot++) {
				if (inv.getStack(slot).isEmpty()) {
					inv.setStack(slot, compass);
					break;
				}
			}

			updateCompass(hunter, runner);
		}
	}
}