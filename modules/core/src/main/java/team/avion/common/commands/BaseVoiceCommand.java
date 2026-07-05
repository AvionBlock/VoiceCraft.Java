package team.avion.common.commands;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.lang.NumberFormatException;

import team.avion.common.BaseVoiceCraftJava;
import team.avion.common.utils.IntegerOperation;
import team.avion.common.utils.StringOperation;
import team.avion.common.utils.DoubleStringOperation;

public class BaseVoiceCommand {
    private final BaseVoiceCraftJava plugin;

    // Get the plugin and lang interfaces.
    public <T extends BaseVoiceCraftJava> BaseVoiceCommand(T plugin) {
        this.plugin = plugin;
    }

    public boolean onCommand(String[] args, boolean isConnected, boolean isPlayer, StringOperation hasPermission, DoubleStringOperation sendMessage, IntegerOperation bindUser) {
        if (args.length >= 1) {
            // Connect command - connect to the server.
            if (args[0].equalsIgnoreCase("connect") && hasPermission.execute("voice.connect")) {
                if (args.length >= 4) {
                    String newHost = args[1];
                    String newPortString = args[2];
                    Integer newPort = -1;
                    String newKey = args[3];
                    try {
                        if (Objects.nonNull(newPortString)) {
                            newPort = Integer.parseInt(newPortString);
                        }
                    } catch (NumberFormatException e) {
                        newPort = -1;
                    }

                    if (Objects.nonNull(newHost) && Objects.nonNull(newPortString) && Objects.nonNull(newKey) && newPort != -1) {
                        sendMessage.execute("plugin-connect-connecting", "yellow");
                        Boolean connected = plugin.connect(newHost, newPort, newKey);

                        // Console will always get a message, so only send message when player sending command!
                        if (isPlayer) {
                            if (connected)
                                sendMessage.execute("plugin-connect-connected", "green");
                            else
                                sendMessage.execute("plugin-connect-failed", "red");
                        }
                        return true;
                    }
                }
                sendMessage.execute("commands.connect.invalid-args", "red");
            }
            // Reconnect command - (re)connect to the VoiceCraft server.
            else if (args[0].equalsIgnoreCase("reconnect") && hasPermission.execute("voice.reconnect")) {
                Boolean force = false;
                if (args.length >= 2 && Objects.nonNull(args[1])) {
                    force = Boolean.valueOf(args[1]);
                }

                sendMessage.execute("plugin-connect-connecting", "yellow");
                Boolean connected = plugin.reconnect(force);
                // Console will always get a message, so only send message when player sending command!
                if (isPlayer) {
                    if (connected)
                        sendMessage.execute("plugin-connect-connected", "green");
                    else 
                        sendMessage.execute("plugin-connect-failed", "red");
                }
            }
            // Disconnect command - disconnect from the VoiceCraft server.
            else if (args[0].equalsIgnoreCase("disconnect") && hasPermission.execute("voice.disconnect")) {
                sendMessage.execute("commands.disconnect.disconnecting", "yellow");
                if (!isConnected) {
                    sendMessage.execute("commands.disconnect.already-disconnected", "red");
                    return true;
                }

                plugin.disconnect("Disconnection Request.");
            }
            // Bind command - bind player.
            else if (args[0].equalsIgnoreCase("bind") && hasPermission.execute("voice.bind") && isConnected) {
                if (isPlayer) {
                    if (args.length >= 2 && Objects.nonNull(args[1])) {
                        int bindKey;
                        try {
                            bindKey = Integer.parseInt(args[1]);
                        } catch (NumberFormatException e) {
                            sendMessage.execute("commands.bind.invalid-args", "red");
                            return true;
                        }

                        sendMessage.execute("commands.bind.binding", "yellow");
                        Boolean isBound = bindUser.execute(bindKey);
                        if (isBound) {
                            sendMessage.execute("commands.bind.binded", "green");
                        } else {
                            sendMessage.execute("commands.bind.failed", "red");
                        }
                    } else {
                        sendMessage.execute("commands.bind.invalid-args", "red");
                    }
                }
                // Player only command.
                else {
                    sendMessage.execute("cmd-not-player", "red");
                }
            }
            // Bind fake command - bind fake player.
            else if (args[0].equalsIgnoreCase("bindfake") && hasPermission.execute("voice.bindfake") && isConnected) {
                if (args.length >= 3 && Objects.nonNull(args[1]) && Objects.nonNull(args[2])) {
                    int bindKey;
                    try {
                        bindKey = Integer.parseInt(args[1]);
                    } catch (NumberFormatException e) {
                        sendMessage.execute("commands.bind.fake-invalid-args", "red");
                        return true;
                    }
                    String name = args[2];

                    sendMessage.execute("commands.bind.binding-fake", "yellow");
                    Boolean isBound = plugin.bindFake(bindKey, name);
                    if (isBound) {
                        sendMessage.execute("commands.bind.binded", "green");
                    } else {
                        sendMessage.execute("commands.bind.failed", "red");
                    }
                } else {
                    sendMessage.execute("commands.bind.fake-invalid-args", "red");
                }
            }
            // Reload command - reload the configs.
            else if (args[0].equalsIgnoreCase("reload") && hasPermission.execute("voice.reload")) {
                plugin.reload();
                sendMessage.execute("commands.reload", "green");
            }
            else {
                sendMessage.execute("cmd-invalid-args", "red");
            }
        }
        // Invalid command arguments.
        else {
            sendMessage.execute("cmd-invalid-args", "red");
        }
        return true;
    }

    public List<String> onTabComplete(String[] args, StringOperation hasPermission) {
        List<String> completions = List.of();

        // Main command arguments.
        if (args.length == 1 || args.length == 0) {
            List<String> options = List.of("connect", "reconnect", "disconnect", "bind", "bindfake", "reload");
            completions = options.stream()
                    .filter(val -> (args.length == 0 || val.startsWith(args[0]))
                            && hasPermission.execute("voice." + val))
                    .collect(Collectors.toList());
        }

        // Setup command arguments.
        if (args.length == 2 && args[0].equalsIgnoreCase("connect") && hasPermission.execute("voice.connect")) {
            List<String> options = List.of("host port key");
            completions = options.stream().filter(val -> val.startsWith(args[1])).collect(Collectors.toList());
        }

        // Connect command arguments.
        if (args.length == 2 && args[0].equalsIgnoreCase("reconnect") && hasPermission.execute("voice.reconnect")) {
            List<String> options = List.of("true", "false");
            completions = options.stream().filter(val -> val.startsWith(args[1])).collect(Collectors.toList());
        }

        return completions;
    }
}
