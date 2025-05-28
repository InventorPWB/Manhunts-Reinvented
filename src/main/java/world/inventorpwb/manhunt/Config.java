package world.inventorpwb.manhunt;

import eu.midnightdust.lib.config.MidnightConfig;

public class Config extends MidnightConfig {
    @Entry(category = "timings") public static int secondsBeforeRelease = 30;
    @Entry(category = "timings") public static int updateCompassEach = 15;
    @Entry(category = "gameplay") public static boolean removePiglinBrutes = false;
}
