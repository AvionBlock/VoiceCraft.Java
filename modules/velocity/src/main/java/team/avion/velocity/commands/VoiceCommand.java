package team.avion.velocity.commands;

import java.util.List;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import team.avion.common.commands.BaseVoiceCommand;
import team.avion.common.utils.DoubleStringOperation;
import team.avion.common.utils.IntegerOperation;
import team.avion.common.utils.StringOperation;
import team.avion.velocity.VoiceCraftJava;
import team.avion.common.localization.Language;

public final class VoiceCommand implements SimpleCommand {
    private final BaseVoiceCommand voiceCommand;
    private final VoiceCraftJava plugin;
    private final String lang;

    public VoiceCommand(VoiceCraftJava plugin, String lang) {
        this.voiceCommand = new BaseVoiceCommand(plugin);
        this.plugin = plugin;
        this.lang = lang;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource sender = invocation.source();
        String[] args = invocation.arguments();

        voiceCommand.onCommand(args, plugin.isConnected(), sender instanceof Player,
                permission -> allowed(sender, permission),
                new DoubleStringOperation() {
                    @Override
                    public void execute(String text, String rawColor) {
                        NamedTextColor color = NamedTextColor.RED;
                        if ("aqua".equals(rawColor)) {
                            color = NamedTextColor.AQUA;
                        } else if ("green".equals(rawColor)) {
                            color = NamedTextColor.GREEN;
                        } else if ("yellow".equals(rawColor)) {
                            color = NamedTextColor.YELLOW;
                        }

                        Component message = Component.text(Language.getMessage(lang, text)).color(color);
                        if (sender instanceof Player) {
                            sender.sendMessage(message);
                        } else {
                            plugin.Logger.log(message);
                        }
                    }
                },
                new IntegerOperation() {
                    @Override
                    public boolean execute(int key) {
                        return sender instanceof Player player && plugin.bind(key, player);
                    }
                });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return allowed(invocation.source(), "voice.cmd");
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return voiceCommand.onTabComplete(invocation.arguments(), new StringOperation() {
            @Override
            public boolean execute(String permission) {
                return allowed(invocation.source(), permission);
            }
        });
    }

    private static boolean allowed(CommandSource sender, String permission) {
        if (!(sender instanceof Player)) {
            return true;
        }
        if ("voice.cmd".equals(permission) || "voice.bind".equals(permission)) {
            return sender.getPermissionValue(permission) != Tristate.FALSE;
        }
        return sender.hasPermission(permission);
    }
}
