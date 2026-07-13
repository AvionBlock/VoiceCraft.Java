package team.avion.bungeecord.commands;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;
import team.avion.bungeecord.VoiceCraftJava;
import team.avion.common.localization.Language;
import team.avion.common.commands.BaseVoiceCommand;
import team.avion.common.utils.DoubleStringOperation;
import team.avion.common.utils.IntegerOperation;
import team.avion.common.utils.StringOperation;

public class VoiceCommand extends Command implements TabExecutor {
    private final BaseVoiceCommand voiceCommand;
    private final VoiceCraftJava plugin;
    private final String lang;

    public VoiceCommand(VoiceCraftJava plugin, String lang) {
        super("voice");
        this.voiceCommand = new BaseVoiceCommand(plugin);
        this.plugin = plugin;
        this.lang = lang;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        voiceCommand.onCommand(args, plugin.isConnected(), sender instanceof ProxiedPlayer,
                permission -> !(sender instanceof ProxiedPlayer) || sender.hasPermission(permission),
                new DoubleStringOperation() {
                    @Override
                    public void execute(String text, String rawColor) {
                        ChatColor color = ChatColor.RED;
                        if ("aqua".equals(rawColor)) {
                            color = ChatColor.AQUA;
                        } else if ("green".equals(rawColor)) {
                            color = ChatColor.GREEN;
                        } else if ("yellow".equals(rawColor)) {
                            color = ChatColor.YELLOW;
                        }

                        if (sender instanceof ProxiedPlayer) {
                            sender.sendMessage(new ComponentBuilder(Language.getMessage(lang, text)).color(color).create());
                        } else {
                            plugin.Logger.info(Language.getMessage(lang, text));
                        }
                    }
                },
                new IntegerOperation() {
                    @Override
                    public boolean execute(int key) {
                        return sender instanceof ProxiedPlayer player && plugin.bind(key, player);
                    }
                });
    }

    @Override
    public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        return voiceCommand.onTabComplete(args, new StringOperation() {
            @Override
            public boolean execute(String permission) {
                return sender.hasPermission(permission);
            }
        });
    }
}
