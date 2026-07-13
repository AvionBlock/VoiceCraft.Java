package team.avion.proxy;

import team.avion.common.utils.BaseLogger;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VoiceProxyUdpRelayManager implements Closeable {
    public static final String ADAPTER_PLASMO = "plasmo";
    public static final String ADAPTER_SIMPLE_VOICE_CHAT = "simple-voice-chat";

    private static final byte SVC_MAGIC = (byte) 0xFF;
    private static final int PLASMO_UDP_MAGIC = 0x4e9004e9;
    private static final int DEFAULT_PACKET_SIZE = 4096;

    private final BaseLogger logger;
    private final Map<String, Relay> relaysByAdapter = new ConcurrentHashMap<>();
    private final Map<RouteKey, BackendRoute> routes = new ConcurrentHashMap<>();

    public VoiceProxyUdpRelayManager(BaseLogger logger) {
        this.logger = logger;
    }

    public void configure(String adapterId, boolean enabled, String bindHost, int bindPort) {
        Relay existing = relaysByAdapter.remove(adapterId);
        if (existing != null) {
            existing.close();
        }

        if (!enabled) {
            return;
        }
        if (bindPort <= 0) {
            logger.warnLocalized("proxy.invalid-bind-port", "$adapter", adapterId, "$port", Integer.toString(bindPort));
            return;
        }

        KeyExtractor keyExtractor = switch (adapterId) {
            case ADAPTER_PLASMO -> VoiceProxyUdpRelayManager::extractPlasmoSecret;
            case ADAPTER_SIMPLE_VOICE_CHAT -> VoiceProxyUdpRelayManager::extractSimpleVoiceChatPlayerId;
            default -> throw new IllegalArgumentException("Unknown UDP relay adapter " + adapterId);
        };

        Relay relay = new Relay(adapterId, blankToDefault(bindHost, "0.0.0.0"), bindPort, keyExtractor);
        try {
            relay.start();
            relaysByAdapter.put(adapterId, relay);
            logger.infoLocalized("proxy.relay-started", "$adapter", adapterId, "$host", relay.bindHost,
                    "$port", Integer.toString(relay.bindPort));
        } catch (IOException exception) {
            relay.close();
            logger.warnLocalized("proxy.relay-start-failed", "$adapter", adapterId, "$error", exception.getMessage());
        }
    }

    public void registerRoute(String adapterId, String routeKey, String backendHost, int backendPort) {
        if (!relaysByAdapter.containsKey(adapterId)) {
            return;
        }
        if (routeKey == null || routeKey.isBlank() || backendHost == null || backendHost.isBlank()
                || backendPort <= 0) {
            logger.warnLocalized("proxy.invalid-route", "$adapter", adapterId);
            return;
        }

        routes.put(new RouteKey(adapterId, routeKey), new BackendRoute(backendHost, backendPort));
        logger.debugLocalized("proxy.route-registered", "$adapter", adapterId, "$route", routeKey.toString(),
                "$host", backendHost, "$port", Integer.toString(backendPort));
    }

    public void unregisterRoute(String adapterId, String routeKey) {
        routes.remove(new RouteKey(adapterId, routeKey));
        Relay relay = relaysByAdapter.get(adapterId);
        if (relay != null) {
            relay.closeConnection(routeKey);
        }
    }

    @Override
    public void close() {
        relaysByAdapter.values().forEach(Relay::close);
        relaysByAdapter.clear();
        routes.clear();
    }

    private BackendRoute resolveRoute(String adapterId, String routeKey) {
        return routes.get(new RouteKey(adapterId, routeKey));
    }

    private static Optional<String> extractSimpleVoiceChatPlayerId(byte[] data, int length) {
        if (length < 17 || data[0] != SVC_MAGIC) {
            return Optional.empty();
        }
        ByteBuffer input = ByteBuffer.wrap(data, 1, 16);
        return Optional.of(new UUID(input.getLong(), input.getLong()).toString());
    }

    private static Optional<String> extractPlasmoSecret(byte[] data, int length) {
        if (length < 21) {
            return Optional.empty();
        }
        ByteBuffer input = ByteBuffer.wrap(data, 0, length);
        if (input.getInt() != PLASMO_UDP_MAGIC) {
            return Optional.empty();
        }
        input.get();
        return Optional.of(new UUID(input.getLong(), input.getLong()).toString());
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @FunctionalInterface
    private interface KeyExtractor {
        Optional<String> extract(byte[] data, int length);
    }

    private record RouteKey(String adapterId, String routeKey) {
    }

    private record BackendRoute(String host, int port) {
        InetSocketAddress address() {
            return new InetSocketAddress(host, port);
        }
    }

    private final class Relay implements Closeable {
        private final String adapterId;
        private final String bindHost;
        private final int bindPort;
        private final KeyExtractor keyExtractor;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final Map<String, RelayConnection> connections = new ConcurrentHashMap<>();
        private DatagramSocket publicSocket;
        private Thread thread;

        private Relay(String adapterId, String bindHost, int bindPort, KeyExtractor keyExtractor) {
            this.adapterId = adapterId;
            this.bindHost = bindHost;
            this.bindPort = bindPort;
            this.keyExtractor = keyExtractor;
        }

        private void start() throws SocketException {
            publicSocket = new DatagramSocket(new InetSocketAddress(bindHost, bindPort));
            publicSocket.setSoTimeout(1000);
            running.set(true);
            thread = new Thread(this::run, "VoiceCraft.Java-" + adapterId + "-UDP-Relay");
            thread.setDaemon(true);
            thread.start();
        }

        private void run() {
            byte[] buffer = new byte[DEFAULT_PACKET_SIZE];
            while (running.get()) {
                DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                try {
                    publicSocket.receive(datagram);
                    handleClientDatagram(datagram);
                } catch (SocketTimeoutException ignored) {
                    // Timeout keeps close() responsive.
                } catch (SocketException exception) {
                    if (running.get()) {
                        logger.warnLocalized("proxy.relay-socket-failed", "$adapter", adapterId, "$error", exception.getMessage());
                    }
                } catch (Exception exception) {
                    logger.debugLocalized("proxy.invalid-packet", "$adapter", adapterId, "$error", exception.getMessage());
                }
            }
        }

        private void handleClientDatagram(DatagramPacket datagram) throws IOException {
            byte[] payload = new byte[datagram.getLength()];
            System.arraycopy(datagram.getData(), datagram.getOffset(), payload, 0, datagram.getLength());
            Optional<String> routeKey = keyExtractor.extract(payload, payload.length);
            if (routeKey.isEmpty()) {
                return;
            }

            BackendRoute route = resolveRoute(adapterId, routeKey.get());
            if (route == null) {
                logger.debugLocalized("proxy.route-not-found", "$adapter", adapterId, "$route", routeKey.get().toString());
                return;
            }

            RelayConnection connection = connections.compute(routeKey.get(), (key, existing) -> {
                if (existing != null && existing.matches(route)) {
                    return existing;
                }
                if (existing != null) {
                    existing.close();
                }
                try {
                    return new RelayConnection(key, route);
                } catch (SocketException exception) {
                    logger.warnLocalized("proxy.backend-socket-open-failed", "$adapter", adapterId, "$error", exception.getMessage());
                    return null;
                }
            });
            if (connection == null) {
                return;
            }

            connection.clientAddress = datagram.getSocketAddress();
            connection.sendToBackend(payload);
        }

        private void closeConnection(String routeKey) {
            RelayConnection connection = connections.remove(routeKey);
            if (connection != null) {
                connection.close();
            }
        }

        @Override
        public void close() {
            running.set(false);
            connections.values().forEach(RelayConnection::close);
            connections.clear();
            if (publicSocket != null) {
                publicSocket.close();
            }
            if (thread != null) {
                try {
                    thread.join(2000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private final class RelayConnection implements Closeable {
            private final String routeKey;
            private final BackendRoute route;
            private final DatagramSocket backendSocket;
            private final AtomicBoolean open = new AtomicBoolean(true);
            private volatile SocketAddress clientAddress;
            private final Thread backendThread;

            private RelayConnection(String routeKey, BackendRoute route) throws SocketException {
                this.routeKey = routeKey;
                this.route = route;
                this.backendSocket = new DatagramSocket();
                this.backendSocket.setSoTimeout(1000);
                this.backendThread = new Thread(this::runBackendLoop,
                        "VoiceCraft.Java-" + adapterId + "-UDP-Backend-" + routeKey);
                this.backendThread.setDaemon(true);
                this.backendThread.start();
            }

            private boolean matches(BackendRoute other) {
                return Objects.equals(route, other);
            }

            private void sendToBackend(byte[] payload) throws IOException {
                DatagramPacket packet = new DatagramPacket(payload, payload.length, route.address());
                backendSocket.send(packet);
            }

            private void runBackendLoop() {
                byte[] buffer = new byte[DEFAULT_PACKET_SIZE];
                while (open.get() && running.get()) {
                    DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                    try {
                        backendSocket.receive(datagram);
                        SocketAddress target = clientAddress;
                        if (target == null) {
                            continue;
                        }
                        byte[] payload = new byte[datagram.getLength()];
                        System.arraycopy(datagram.getData(), datagram.getOffset(), payload, 0, datagram.getLength());
                        publicSocket.send(new DatagramPacket(payload, payload.length, target));
                    } catch (SocketTimeoutException ignored) {
                        // Timeout keeps close() responsive.
                    } catch (SocketException exception) {
                        if (open.get() && running.get()) {
                            logger.warnLocalized("proxy.backend-relay-failed", "$adapter", adapterId, "$route", routeKey.toString(),
                                    "$error", exception.getMessage());
                        }
                    } catch (IOException exception) {
                        logger.debugLocalized("proxy.backend-packet-failed", "$adapter", adapterId, "$route", routeKey.toString(),
                                "$error", exception.getMessage());
                    }
                }
            }

            @Override
            public void close() {
                open.set(false);
                backendSocket.close();
                try {
                    backendThread.join(2000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
