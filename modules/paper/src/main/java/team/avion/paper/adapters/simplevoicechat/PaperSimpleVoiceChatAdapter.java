package team.avion.paper.adapters.simplevoicechat;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import team.avion.adapter.AdapterContext;
import team.avion.adapter.AdapterState;
import team.avion.adapter.VoiceAdapter;
import team.avion.adapter.simplevoicechat.SimpleVoiceChatChannels;
import team.avion.adapter.simplevoicechat.net.AuthenticatePacket;
import team.avion.adapter.simplevoicechat.net.MicPacket;
import team.avion.adapter.simplevoicechat.net.SimplePacket;
import team.avion.adapter.simplevoicechat.net.SimpleVoiceChatPacketType;
import team.avion.adapter.simplevoicechat.net.SimpleVoiceChatTcpCodec;
import team.avion.adapter.simplevoicechat.net.SimpleVoiceChatUdpCodec;
import team.avion.adapter.simplevoicechat.net.SimpleVoiceChatUdpEnvelope;
import team.avion.adapter.simplevoicechat.session.SimpleVoiceChatClientSession;
import team.avion.adapter.simplevoicechat.session.SimpleVoiceChatSessionManager;
import team.avion.paper.VoiceCraftJava;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PaperSimpleVoiceChatAdapter implements VoiceAdapter, PluginMessageListener, Listener {
    private static final long KEEP_ALIVE_TIMEOUT_FACTOR = 10L;

    private final VoiceCraftJava plugin;
    private final SimpleVoiceChatTcpCodec tcpCodec = new SimpleVoiceChatTcpCodec();
    private final SimpleVoiceChatUdpCodec udpCodec = new SimpleVoiceChatUdpCodec();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private AdapterState state = AdapterState.NEW;
    private SimpleVoiceChatSessionManager sessions;
    private DatagramSocket udpSocket;
    private Thread udpThread;
    private String bindHost;
    private String voiceHost;
    private int udpPort;
    private int publicPort;
    private int compatibilityVersion;
    private int mtuSize;
    private int keepAliveMillis;
    private double voiceDistance;
    private boolean allowRecording;
    private boolean debug;

    public PaperSimpleVoiceChatAdapter(VoiceCraftJava plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "simple-voice-chat";
    }

    @Override
    public String displayName() {
        return "Simple Voice Chat";
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
        this.sessions = new SimpleVoiceChatSessionManager(context.audioBridge());
        this.bindHost = plugin.getConfig().getString("config.adapters.simple-voice-chat.bind-host", "0.0.0.0");
        this.udpPort = plugin.getConfig().getInt("config.adapters.simple-voice-chat.port", 24454);
        this.voiceHost = plugin.getConfig().getString("config.adapters.simple-voice-chat.public.host", "");
        int configuredPublicPort = plugin.getConfig().getInt("config.adapters.simple-voice-chat.public.port", 0);
        this.publicPort = configuredPublicPort > 0 ? configuredPublicPort : udpPort;
        this.compatibilityVersion = plugin.getConfig().getInt("config.adapters.simple-voice-chat.compatibility-version", 20);
        this.mtuSize = plugin.getConfig().getInt("config.adapters.simple-voice-chat.mtu-size", 1024);
        this.keepAliveMillis = plugin.getConfig().getInt("config.adapters.simple-voice-chat.keep-alive-ms", 1000);
        this.voiceDistance = plugin.getConfig().getDouble("config.voice.proximity-distance", 30D);
        this.allowRecording = plugin.getConfig().getBoolean("config.adapters.simple-voice-chat.allow-recording", true);
        this.debug = plugin.getConfig().getBoolean("config.debug", false);

        udpSocket = new DatagramSocket(new InetSocketAddress(bindHost, udpPort));
        udpSocket.setSoTimeout(1000);
        running.set(true);
        udpThread = new Thread(this::runUdpLoop, "VoiceCraft.Java-SimpleVoiceChat-UDP");
        udpThread.setDaemon(true);
        udpThread.start();

        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, SimpleVoiceChatChannels.REQUEST_SECRET, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, SimpleVoiceChatChannels.SECRET);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        state = AdapterState.RUNNING;
        plugin.Logger.info("Simple Voice Chat adapter listening on " + bindHost + ":" + udpPort
                + " and advertising " + (voiceHost.isBlank() ? "<minecraft host>" : voiceHost) + ":" + publicPort + ".");
    }

    @Override
    public void stop() {
        if (!running.getAndSet(false)) {
            state = AdapterState.STOPPED;
            return;
        }

        state = AdapterState.STOPPING;
        HandlerList.unregisterAll(this);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, SimpleVoiceChatChannels.REQUEST_SECRET, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, SimpleVoiceChatChannels.SECRET);

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
        if (!SimpleVoiceChatChannels.REQUEST_SECRET.equals(channel)) {
            return;
        }

        try {
            int clientCompatibilityVersion = tcpCodec.decodeRequestSecret(message);
            if (clientCompatibilityVersion != compatibilityVersion) {
                plugin.Logger.warn("Simple Voice Chat client " + player.getName()
                        + " has incompatible compatibility version " + clientCompatibilityVersion
                        + " (server=" + compatibilityVersion + ").");
                return;
            }
            handleRequestSecret(player);
        } catch (Exception exception) {
            plugin.Logger.warn("Failed to decode Simple Voice Chat packet from " + player.getName()
                    + ": " + exception.getMessage());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (sessions.byPlayerId(event.getPlayer().getUniqueId()).isPresent()) {
            sessions.remove(event.getPlayer().getUniqueId());
            plugin.getSessionManager().destroyEntityForPlayer(event.getPlayer().getName());
        }
    }

    private void handleRequestSecret(Player player) {
        if (!plugin.isConnected()) {
            plugin.Logger.warn("Simple Voice Chat client " + player.getName() + " joined before VoiceCraft was connected.");
            return;
        }

        OptionalInt entityId = plugin.getSessionManager().createEntityForPlayer(player);
        if (entityId.isEmpty()) {
            String reason = plugin.getSessionManager().getLastCreateEntityFailure();
            plugin.Logger.warn("Failed to create VoiceCraft entity for Simple Voice Chat client " + player.getName()
                    + (reason.isBlank() ? "." : ": " + reason + "."));
            return;
        }

        SimpleVoiceChatClientSession session = sessions.createOrReplace(
                player.getUniqueId(),
                player.getName(),
                entityId.getAsInt()
        );
        byte[] payload = tcpCodec.encodeSecret(
                session.secret(),
                publicPort,
                player.getUniqueId(),
                mtuSize,
                voiceDistance,
                keepAliveMillis,
                voiceHost == null ? "" : voiceHost,
                allowRecording
        );
        player.sendPluginMessage(plugin, SimpleVoiceChatChannels.SECRET, payload);
        debug("Sent Simple Voice Chat secret to " + player.getName() + " entity=" + entityId.getAsInt() + ".");
    }

    private void runUdpLoop() {
        byte[] buffer = new byte[4096];
        while (running.get()) {
            DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
            try {
                udpSocket.receive(datagram);
                byte[] payload = new byte[datagram.getLength()];
                System.arraycopy(datagram.getData(), datagram.getOffset(), payload, 0, datagram.getLength());
                handleUdpDatagram(payload, datagram.getSocketAddress());
                sendKeepAlives();
            } catch (SocketTimeoutException ignored) {
                sendKeepAlives();
            } catch (SocketException exception) {
                if (running.get()) {
                    plugin.Logger.warn("Simple Voice Chat UDP socket failed: " + exception.getMessage());
                }
            } catch (Exception exception) {
                plugin.Logger.warn("Failed to handle Simple Voice Chat UDP packet: " + exception.getMessage());
            }
        }
    }

    private void handleUdpDatagram(byte[] payload, java.net.SocketAddress remoteAddress) throws Exception {
        SimpleVoiceChatUdpEnvelope envelope = udpCodec.decodeClient(payload, remoteAddress,
                playerId -> sessions.byPlayerId(playerId).map(SimpleVoiceChatClientSession::secret).orElse(null));
        Optional<SimpleVoiceChatClientSession> optionalSession = sessions.byPlayerId(envelope.playerId());
        if (optionalSession.isEmpty()) {
            debug("Ignored Simple Voice Chat UDP packet for unknown player " + envelope.playerId() + ".");
            return;
        }

        SimpleVoiceChatClientSession session = optionalSession.get();
        session.touch(remoteAddress);
        if (envelope.packet() instanceof AuthenticatePacket authenticatePacket) {
            if (!authenticatePacket.playerId().equals(session.playerId())) {
                debug("Ignored Simple Voice Chat auth with mismatched player id " + authenticatePacket.playerId() + ".");
                return;
            }
            sendUdpPacket(new SimplePacket(SimpleVoiceChatPacketType.AUTHENTICATE_ACK), session);
            debug("Authenticated Simple Voice Chat UDP session for " + session.playerName() + ".");
        } else if (envelope.packet() instanceof SimplePacket simplePacket
                && simplePacket.type() == SimpleVoiceChatPacketType.CONNECTION_CHECK) {
            session.markConnected();
            sendUdpPacket(new SimplePacket(SimpleVoiceChatPacketType.CONNECTION_CHECK_ACK), session);
            debug("Validated Simple Voice Chat UDP session for " + session.playerName() + ".");
        } else if (envelope.packet() instanceof SimplePacket simplePacket
                && simplePacket.type() == SimpleVoiceChatPacketType.KEEP_ALIVE) {
            debug("Received Simple Voice Chat keepalive from " + session.playerName() + ".");
        } else if (envelope.packet() instanceof MicPacket micPacket) {
            sessions.forwardAudio(session, micPacket);
        }
    }

    private void sendKeepAlives() {
        long now = System.currentTimeMillis();
        for (SimpleVoiceChatClientSession session : sessions.sessions()) {
            if (session.remoteAddress() == null || !session.connected()) {
                continue;
            }
            if (now - session.lastSeenMillis() >= keepAliveMillis * KEEP_ALIVE_TIMEOUT_FACTOR) {
                debug("Simple Voice Chat UDP session timed out for " + session.playerName() + ".");
                sessions.remove(session.playerId());
                continue;
            }
            if (now - session.lastKeepAliveSentMillis() < keepAliveMillis) {
                continue;
            }

            try {
                sendUdpPacket(new SimplePacket(SimpleVoiceChatPacketType.KEEP_ALIVE), session);
                session.markKeepAliveSent();
            } catch (Exception exception) {
                plugin.Logger.warn("Failed to send Simple Voice Chat keepalive to " + session.playerName()
                        + ": " + exception.getMessage());
            }
        }
    }

    private void sendUdpPacket(SimplePacket packet, SimpleVoiceChatClientSession session) throws Exception {
        byte[] encoded = udpCodec.encodeServer(packet, session.secret());
        DatagramPacket datagram = new DatagramPacket(encoded, encoded.length);
        datagram.setSocketAddress(session.remoteAddress());
        udpSocket.send(datagram);
    }

    private void debug(String message) {
        if (debug) {
            plugin.Logger.info("[SimpleVoiceChatAdapter] " + message);
        }
    }
}
