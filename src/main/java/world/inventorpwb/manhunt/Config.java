package world.inventorpwb.manhunt;

import eu.midnightdust.lib.config.MidnightConfig;

public class Config extends MidnightConfig {
    @Entry(category = "timings") public static int secondsBeforeRelease = 30;
    @Entry(category = "timings") public static int updateCompassEach = 5;
    @Entry(category = "gameplay") public static boolean removePiglinBrutes = false;
    @Entry(category = "gameplay") public static boolean disableImpostorGameChat = true;
    @Entry(category = "gameplay") public static boolean disableMessaging = false;
    @Entry(category = "gameplay") public static int maximumReveals = 1;
    @Entry(category = "gameplay") public static boolean hunterChat = true;
    @Entry(category = "gameplay") public static boolean speedrunnerChat = true;
    @Entry(category = "gameplay") public static boolean enableWorldBorder = true;
    @Entry(category = "gameplay") public static boolean enableOnePlayerSleeping = true;
}
