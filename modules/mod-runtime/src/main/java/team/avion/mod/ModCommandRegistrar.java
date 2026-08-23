package team.avion.mod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import team.avion.common.localization.Language;

/** Loader-neutral registration of the VoiceCraft server commands. */
public final class ModCommandRegistrar {
    private ModCommandRegistrar() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                AbstractVoiceCraftMod plugin,
                                PermissionResolver permissions) {
        dispatcher.register(Commands.literal("voice")
                .requires(source -> permissions.has(source, "voice.cmd", 0))
                .then(Commands.literal("reload")
                        .requires(source -> permissions.has(source, "voice.reload", 2))
                        .executes(context -> {
                            plugin.reload();
                            send(plugin, context.getSource(), "commands.reload");
                            return 1;
                        }))
                .then(Commands.literal("disconnect")
                        .requires(source -> permissions.has(source, "voice.disconnect", 2))
                        .executes(context -> {
                            plugin.disconnect("Disconnection Request.");
                            send(plugin, context.getSource(), "commands.disconnect.disconnecting");
                            return 1;
                        }))
                .then(Commands.literal("reconnect")
                        .requires(source -> permissions.has(source, "voice.reconnect", 2))
                        .executes(context -> reconnect(plugin, context.getSource(), false))
                        .then(Commands.argument("force", StringArgumentType.word())
                                .executes(context -> reconnect(plugin, context.getSource(),
                                        Boolean.parseBoolean(StringArgumentType.getString(context, "force"))))))
                .then(Commands.literal("connect")
                        .requires(source -> permissions.has(source, "voice.connect", 2))
                        .then(Commands.argument("host", StringArgumentType.word())
                                .then(Commands.argument("port", IntegerArgumentType.integer(1, 65535))
                                        .then(Commands.argument("token", StringArgumentType.word())
                                                .executes(context -> {
                                                    boolean active = plugin.connect(
                                                            StringArgumentType.getString(context, "host"),
                                                            IntegerArgumentType.getInteger(context, "port"),
                                                            StringArgumentType.getString(context, "token"));
                                                    send(plugin, context.getSource(), active
                                                            ? "plugin-connect-connected"
                                                            : "plugin-connect-failed");
                                                    return active ? 1 : 0;
                                                })))))
                .then(Commands.literal("bind")
                        .requires(source -> permissions.has(source, "voice.bind", 0))
                        .then(Commands.argument("key", IntegerArgumentType.integer(0))
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayer();
                                    boolean bound = plugin.bind(IntegerArgumentType.getInteger(context, "key"), player);
                                    send(plugin, context.getSource(), bound
                                            ? "commands.bind.binded"
                                            : "commands.bind.failed");
                                    return bound ? 1 : 0;
                                })))
                .then(Commands.literal("bindfake")
                        .requires(source -> permissions.has(source, "voice.bindfake", 2))
                        .then(Commands.argument("key", IntegerArgumentType.integer(0))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(context -> {
                                            boolean bound = plugin.bindFake(
                                                    IntegerArgumentType.getInteger(context, "key"),
                                                    StringArgumentType.getString(context, "name"));
                                            send(plugin, context.getSource(), bound
                                                    ? "commands.bind.binded"
                                                    : "commands.bind.failed");
                                            return bound ? 1 : 0;
                                        })))));
    }

    private static int reconnect(AbstractVoiceCraftMod plugin, CommandSourceStack source, boolean force) {
        send(plugin, source, "plugin-connect-connecting");
        boolean active = plugin.reconnect(force);
        send(plugin, source, active ? "plugin-connect-connected" : "plugin-connect-failed");
        return active ? 1 : 0;
    }

    private static void send(AbstractVoiceCraftMod plugin, CommandSourceStack source, String key) {
        source.sendSuccess(() -> Component.literal(Language.getMessage(plugin.getLanguage(), key)), false);
    }

    @FunctionalInterface
    public interface PermissionResolver {
        boolean has(CommandSourceStack source, String permission, int defaultLevel);
    }
}
