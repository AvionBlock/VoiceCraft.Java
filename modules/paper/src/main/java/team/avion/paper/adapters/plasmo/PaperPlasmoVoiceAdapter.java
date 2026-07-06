package team.avion.paper.adapters.plasmo;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import team.avion.adapter.AdapterContext;
import team.avion.adapter.AdapterState;
import team.avion.adapter.VoiceAdapter;
import team.avion.adapter.plasmo.PlasmoChannels;
import team.avion.adapter.plasmo.PlasmoPacket;
import team.avion.adapter.plasmo.session.PlasmoClientSession;
import team.avion.adapter.plasmo.session.PlasmoSessionManager;
import team.avion.adapter.plasmo.tcp.PlasmoTcpCodec;
import team.avion.adapter.plasmo.tcp.PlasmoTcpCodecs;
import team.avion.adapter.plasmo.tcp.packet.ConfigPacket;
import team.avion.adapter.plasmo.tcp.packet.ConnectionPacket;
import team.avion.adapter.plasmo.tcp.packet.LanguagePacket;
import team.avion.adapter.plasmo.tcp.packet.LanguageRequestPacket;
import team.avion.adapter.plasmo.tcp.packet.PlayerActivationDistancesPacket;
import team.avion.adapter.plasmo.tcp.packet.PlayerAudioEndPacket;
import team.avion.adapter.plasmo.tcp.packet.PlayerInfoPacket;
import team.avion.adapter.plasmo.tcp.packet.PlayerInfoRequestPacket;
import team.avion.adapter.plasmo.tcp.packet.PlayerListPacket;
import team.avion.adapter.plasmo.tcp.packet.PlayerStatePacket;
import team.avion.adapter.plasmo.tcp.packet.SourceInfoRequestPacket;
import team.avion.adapter.plasmo.udp.PlasmoUdpCodec;
import team.avion.adapter.plasmo.udp.PlasmoUdpCodecs;
import team.avion.adapter.plasmo.udp.PlasmoUdpEnvelope;
import team.avion.adapter.plasmo.udp.packet.PingPacket;
import team.avion.paper.VoiceCraftJava;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;

public final class PaperPlasmoVoiceAdapter implements VoiceAdapter, PluginMessageListener, Listener {
    private static final long KEEP_ALIVE_INTERVAL_MS = 1000L;
    private static final long KEEP_ALIVE_TIMEOUT_MS = 30000L;

    private final VoiceCraftJava plugin;
    private final PlasmoTcpCodec tcpCodec = PlasmoTcpCodecs.createBaseCodec();
    private final PlasmoUdpCodec udpCodec = PlasmoUdpCodecs.createBaseCodec();
    private final UUID serverId = UUID.randomUUID();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private AdapterState state = AdapterState.NEW;
    private PlasmoSessionManager sessions;
    private DatagramSocket udpSocket;
    private Thread udpThread;
    private String bindHost;
    private String publicHost;
    private int udpPort;
    private int publicPort;
    private int proximityDistance;
    private boolean debug;

    public PaperPlasmoVoiceAdapter(VoiceCraftJava plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "plasmo";
    }

    @Override
    public String displayName() {
        return "Plasmo Voice";
    }

    @Override
    public AdapterState state() {
        return state;
    }

    @Override
    public void start(AdapterContext context) throws Exception {
        if (running.get()) {
            return;
        }

        state = AdapterState.STARTING;
        this.sessions = new PlasmoSessionManager(context.audioBridge());
        this.bindHost = plugin.getConfig().getString("config.adapters.plasmo.bind-host", "0.0.0.0");
        this.udpPort = plugin.getConfig().getInt("config.adapters.plasmo.port", 24454);
        this.publicHost = plugin.getConfig().getString("config.adapters.plasmo.public.host",
                plugin.getConfig().getString("config.adapters.plasmo.advertised-host", "0.0.0.0"));
        int configuredPublicPort = plugin.getConfig().getInt("config.adapters.plasmo.public.port", 0);
        this.publicPort = configuredPublicPort > 0 ? configuredPublicPort : udpPort;
        this.proximityDistance = plugin.getConfig().getInt("config.voice.proximity-distance", 30);
        this.debug = plugin.getConfig().getBoolean("config.debug", false);

        udpSocket = new DatagramSocket(new InetSocketAddress(bindHost, udpPort));
        udpSocket.setSoTimeout(1000);
        running.set(true);
        udpThread = new Thread(this::runUdpLoop, "VoiceCraft.Java-Plasmo-UDP");
        udpThread.setDaemon(true);
        udpThread.start();

        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, PlasmoChannels.MAIN);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, PlasmoChannels.FLAG);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, PlasmoChannels.MAIN, this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        state = AdapterState.RUNNING;
        plugin.Logger.info("Plasmo Voice adapter listening on " + bindHost + ":" + udpPort
                + " and advertising " + publicHost + ":" + publicPort + ".");
    }

    @Override
    public void stop() {
        if (!running.getAndSet(false)) {
            state = AdapterState.STOPPED;
            return;
        }

        state = AdapterState.STOPPING;
        HandlerList.unregisterAll(this);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, PlasmoChannels.MAIN, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, PlasmoChannels.MAIN);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, PlasmoChannels.FLAG);

        if (udpSocket != null) {
            udpSocket.close();
        }
        if (udpThread != null) {
            try {
                udpThread.join(2000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        state = AdapterState.STOPPED;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!PlasmoChannels.MAIN.equals(channel)) {
            return;
        }

        try {
            PlasmoPacket packet = tcpCodec.decode(message);
            if (packet instanceof PlayerInfoPacket infoPacket) {
                handlePlayerInfo(player, infoPacket);
            } else if (packet instanceof LanguageRequestPacket languageRequest) {
                sendTcpPacket(player, new LanguagePacket(languageRequest.language(), Map.of()));
            } else if (packet instanceof PlayerActivationDistancesPacket) {
                // Client-side distance preferences are accepted but currently mapped to VoiceCraft proximity settings.
            } else if (packet instanceof PlayerAudioEndPacket audioEndPacket) {
                debug("Plasmo client " + player.getName() + " ended audio sequence="
                        + audioEndPacket.sequenceNumber() + ".");
            } else if (packet instanceof PlayerStatePacket statePacket) {
                debug("Plasmo client " + player.getName() + " state voiceDisabled="
                        + statePacket.voiceDisabled() + " microphoneMuted=" + statePacket.microphoneMuted() + ".");
            } else if (packet instanceof SourceInfoRequestPacket sourceInfoRequest) {
                debug("Plasmo client " + player.getName() + " requested source info "
                        + sourceInfoRequest.sourceId() + ".");
            }
        } catch (Exception exception) {
            plugin.Logger.warn("Failed to decode Plasmo packet from " + player.getName() + ": " + exception.getMessage());
        }
    }

    @EventHandler
    public void onPlayerRegisterChannel(PlayerRegisterChannelEvent event) {
        if (!PlasmoChannels.MAIN.equals(event.getChannel())) {
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> requestPlayerInfo(event.getPlayer()));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (sessions.byPlayerId(event.getPlayer().getUniqueId()).isPresent()) {
            sessions.remove(event.getPlayer().getUniqueId());
            plugin.getSessionManager().destroyEntityForPlayer(event.getPlayer().getName());
        }
    }

    private void requestPlayerInfo(Player player) {
        if (!running.get() || !player.isOnline()) {
            return;
        }
        sendTcpPacket(player, new PlayerInfoRequestPacket());
    }

    private void handlePlayerInfo(Player player, PlayerInfoPacket packet) {
        if (packet.voiceDisabled()) {
            return;
        }
        if (!plugin.isConnected()) {
            plugin.Logger.warn("Plasmo client " + player.getName() + " joined before VoiceCraft was connected.");
            return;
        }

        OptionalInt entityId = plugin.getSessionManager().createEntityForPlayer(player);
        if (entityId.isEmpty()) {
            String reason = plugin.getSessionManager().getLastCreateEntityFailure();
            plugin.Logger.warn("Failed to create VoiceCraft entity for Plasmo client " + player.getName()
                    + (reason.isBlank() ? "." : ": " + reason + "."));
            return;
        }

        PlasmoClientSession session = sessions.createOrReplace(player.getUniqueId(), player.getName(), entityId.getAsInt());
        debug("Created Plasmo session for " + player.getName() + " entity=" + entityId.getAsInt()
                + " secret=" + session.udpSecret() + " endpoint=" + publicHost + ":" + publicPort + ".");
        sendTcpPacket(player, new ConnectionPacket(session.udpSecret(), publicHost, publicPort));
    }

    private void runUdpLoop() {
        byte[] buffer = new byte[4096];
        while (running.get()) {
            DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
            try {
                udpSocket.receive(datagram);
                byte[] payload = new byte[datagram.getLength()];
                System.arraycopy(datagram.getData(), datagram.getOffset(), payload, 0, datagram.getLength());
                debug("Received Plasmo UDP datagram from " + datagram.getSocketAddress() + " bytes=" + payload.length + ".");
                handleUdpDatagram(payload, datagram.getSocketAddress());
                sendKeepAlivePackets();
            } catch (SocketTimeoutException ignored) {
                sendKeepAlivePackets();
            } catch (SocketException exception) {
                if (running.get()) {
                    plugin.Logger.warn("Plasmo UDP socket failed: " + exception.getMessage());
                }
            } catch (Exception exception) {
                plugin.Logger.warn("Failed to handle Plasmo UDP packet: " + exception.getMessage());
            }
        }
    }

    private void handleUdpDatagram(byte[] payload, java.net.SocketAddress remoteAddress) throws IOException {
        PlasmoUdpEnvelope envelope = udpCodec.decode(payload);
        debug("Decoded Plasmo UDP packet type=" + envelope.type() + " secret=" + envelope.secret()
                + " from=" + remoteAddress + ".");
        if (!sessions.handleUdpPacket(envelope, remoteAddress)) {
            debug("Ignored Plasmo UDP packet with unknown secret " + envelope.secret() + ".");
            return;
        }

        if (envelope.packet() instanceof PingPacket) {
            sessions.bySecret(envelope.secret()).ifPresent(session ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (session.configSent()) {
                            return;
                        }
                        Player player = plugin.getServer().getPlayer(session.playerId());
                        if (player == null || !player.isOnline()) {
                            return;
                        }
                        sendTcpPacket(player, new ConfigPacket(serverId, proximityDistance));
                        sendTcpPacket(player, new PlayerListPacket());
                        session.markConfigSent();
                        debug("Sent Plasmo config/player list to " + player.getName() + ".");
                    })
            );
        }
    }

    private void sendKeepAlivePackets() {
        long now = System.currentTimeMillis();
        for (PlasmoClientSession session : sessions.sessions()) {
            java.net.SocketAddress remoteAddress = session.remoteAddress();
            if (remoteAddress == null) {
                continue;
            }
            if (now - session.lastSeenMillis() > KEEP_ALIVE_TIMEOUT_MS) {
                debug("Plasmo UDP session timed out for " + session.playerName() + ".");
                sessions.remove(session.playerId());
                continue;
            }
            if (now - session.lastKeepAliveSentMillis() < KEEP_ALIVE_INTERVAL_MS) {
                continue;
            }

            try {
                sendUdpPacket(new PingPacket(), session.udpSecret(), remoteAddress);
                session.markKeepAliveSent();
                debug("Sent Plasmo UDP keepalive secret=" + session.udpSecret() + " to=" + remoteAddress + ".");
            } catch (IOException exception) {
                plugin.Logger.warn("Failed to send Plasmo UDP keepalive to " + session.playerName() + ": "
                        + exception.getMessage());
            }
        }
    }

    private void sendUdpPacket(PlasmoPacket packet, UUID secret, java.net.SocketAddress remoteAddress) throws IOException {
        byte[] encoded = udpCodec.encode(packet, secret);
        DatagramPacket datagram = new DatagramPacket(encoded, encoded.length);
        datagram.setSocketAddress(remoteAddress);
        udpSocket.send(datagram);
    }

    private void sendTcpPacket(Player player, PlasmoPacket packet) {
        try {
            player.sendPluginMessage(plugin, PlasmoChannels.MAIN, tcpCodec.encode(packet));
            debug("Sent Plasmo TCP packet " + packet.getClass().getSimpleName() + " to " + player.getName() + ".");
        } catch (Exception exception) {
            plugin.Logger.warn("Failed to send Plasmo packet to " + player.getName() + ": " + exception.getMessage());
        }
    }

    private void debug(String message) {
        if (debug) {
            plugin.Logger.info("[PlasmoAdapter] " + message);
        }
    }
}
