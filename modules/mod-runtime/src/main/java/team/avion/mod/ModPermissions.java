package team.avion.mod;

import net.minecraft.commands.CommandSourceStack;

import java.lang.reflect.Method;

/** Permission fallback that remains binary-source compatible across Mojang's 1.20/1.21/26 API changes. */
public final class ModPermissions {
    private ModPermissions() { }

    public static boolean isOperator(CommandSourceStack source, int level) {
        if (level <= 0) return true;
        try {
            Method legacy = source.getClass().getMethod("hasPermission", int.class);
            return (boolean) legacy.invoke(source, level);
        } catch (ReflectiveOperationException ignored) {
            // Minecraft 1.21.10 replaced integer command levels with permission objects.
        }
        try {
            Object entity = source.getClass().getMethod("getEntity").invoke(source);
            if (entity == null) return true;
            Object player = source.getClass().getMethod("getPlayer").invoke(source);
            if (player == null) return false;
            Object server = source.getClass().getMethod("getServer").invoke(source);
            Object playerList = server.getClass().getMethod("getPlayerList").invoke(server);
            Object profile = player.getClass().getMethod("getGameProfile").invoke(player);
            for (Method method : playerList.getClass().getMethods()) {
                if (method.getName().equals("isOp") && method.getParameterCount() == 1) {
                    return (boolean) method.invoke(playerList, profile);
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // Fail closed when a future Minecraft version changes both permission APIs.
        }
        return false;
    }
}
