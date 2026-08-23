package team.avion.fabric.utils;

import net.minecraft.server.level.ServerPlayer;

/** Small boundary for names that changed between Mojang-mapped Minecraft releases. */
public final class FabricMinecraftCompat {
    private FabricMinecraftCompat() {
    }

    public static String dimensionId(ServerPlayer player) {
        String key = player.level().dimension().toString();
        int separator = key.lastIndexOf(" / ");
        if (separator >= 0) {
            key = key.substring(separator + 3);
        }
        while (key.endsWith("]") || key.endsWith(")")) {
            key = key.substring(0, key.length() - 1);
        }
        return key;
    }
}
