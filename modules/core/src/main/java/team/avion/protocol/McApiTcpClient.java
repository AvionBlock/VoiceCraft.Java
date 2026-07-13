package team.avion.protocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import team.avion.common.utils.BaseLogger;
import team.avion.protocol.McPackets.AcceptResponsePacket;
import team.avion.protocol.McPackets.DenyResponsePacket;
import team.avion.protocol.McPackets.LoginRequestPacket;
import team.avion.protocol.McPackets.LogoutRequestPacket;
import team.avion.protocol.McPackets.McApiPacket;
import team.avion.protocol.McProtocolEnums.EventType;

public final class McApiTcpClient {
    private final BaseLogger logger;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private String sessionToken = "";
    private String lastDenyReason = "";

    public McApiTcpClient(BaseLogger logger) {
        this(logger, Duration.ofSeconds(2), Duration.ofSeconds(1));
    }

    public McApiTcpClient(BaseLogger logger, Duration connectTimeout, Duration readTimeout) {
        this.logger = logger;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    public synchronized boolean connect(String host, int port, String loginToken) {
        close();

        try {
            Socket newSocket = new Socket();
            newSocket.connect(new InetSocketAddress(host, port), Math.toIntExact(connectTimeout.toMillis()));
            newSocket.setSoTimeout(Math.toIntExact(readTimeout.toMillis()));

            socket = newSocket;
            input = new DataInputStream(new BufferedInputStream(newSocket.getInputStream()));
            output = new DataOutputStream(new BufferedOutputStream(newSocket.getOutputStream()));
            sessionToken = "";
            lastDenyReason = "";

            LoginRequestPacket loginPacket = new LoginRequestPacket();
            loginPacket.RequestId = nextRequestId();
            loginPacket.Token = loginToken;
            loginPacket.Version = VoiceCraftProtocol.VERSION;
            loginPacket.SubscribeEvents = new EventType[] {
                    EventType.ON_NETWORK_ENTITY_CREATED,
                    EventType.ON_ENTITY_CREATED,
                    EventType.ON_ENTITY_DESTROYED,
                    EventType.ON_ENTITY_MUTE_UPDATED,
                    EventType.ON_ENTITY_DEAFEN_UPDATED,
                    EventType.ON_ENTITY_SERVER_MUTE_UPDATED,
                    EventType.ON_ENTITY_SERVER_DEAFEN_UPDATED,
                    EventType.ON_ENTITY_AUDIO_DATA_RECEIVED
            };

            List<McApiPacket> responsePackets = exchangeInternal("", List.of(loginPacket));
            for (McApiPacket packet : responsePackets) {
                if (packet instanceof AcceptResponsePacket accept) {
                    sessionToken = accept.Token;
                    logger.infoLocalized("mcapi.session-established");
                    return true;
                }
                if (packet instanceof DenyResponsePacket deny) {
                    lastDenyReason = deny.Reason;
                    logger.errorLocalized("mcapi.login-denied", "$reason", deny.Reason);
                    close();
                    return false;
                }
            }

            logger.errorLocalized("mcapi.login-no-accept");
            close();
            return false;
        } catch (Exception ex) {
            logger.errorLocalized("mcapi.connect-failed", "$error", ex.getMessage());
            close();
            return false;
        }
    }

    public synchronized List<McApiPacket> exchange(List<? extends McApiPacket> packets) {
        if (!isConnected()) {
            return List.of();
        }

        try {
            return exchangeInternal(sessionToken, packets);
        } catch (SocketTimeoutException ex) {
            logger.errorLocalized("mcapi.request-timeout", "$error", ex.getMessage());
            close();
            return List.of();
        } catch (Exception ex) {
            logger.errorLocalized("mcapi.request-failed", "$type", ex.getClass().getSimpleName(), "$error", ex.getMessage());
            close();
            return List.of();
        }
    }

    public synchronized void logout() {
        if (!isConnected()) {
            close();
            return;
        }

        try {
            LogoutRequestPacket logoutPacket = new LogoutRequestPacket();
            logoutPacket.Token = sessionToken;
            exchangeInternal(sessionToken, List.of(logoutPacket));
        } catch (Exception exception) {
            logger.debugLocalized("mcapi.logout-failed", "$error", exception.getMessage());
        } finally {
            close();
        }
    }

    public synchronized boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed() && !sessionToken.isBlank();
    }

    public synchronized String getSessionToken() {
        return sessionToken;
    }

    public synchronized String getLastDenyReason() {
        return lastDenyReason;
    }

    public synchronized void close() {
        sessionToken = "";
        lastDenyReason = "";

        if (input != null) {
            try {
                input.close();
            } catch (IOException exception) {
                logger.debugLocalized("mcapi.close-input-failed", "$error", exception.getMessage());
            }
        }
        if (output != null) {
            try {
                output.close();
            } catch (IOException exception) {
                logger.debugLocalized("mcapi.close-output-failed", "$error", exception.getMessage());
            }
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException exception) {
                logger.debugLocalized("mcapi.close-socket-failed", "$error", exception.getMessage());
            }
        }

        input = null;
        output = null;
        socket = null;
    }

    public static String nextRequestId() {
        return UUID.randomUUID().toString();
    }

    private List<McApiPacket> exchangeInternal(String token, List<? extends McApiPacket> packets) throws IOException {
        if (output == null || input == null) {
            throw new IOException("Client socket is not open.");
        }

        List<byte[]> rawPackets = new ArrayList<>(packets.size());
        for (McApiPacket packet : packets) {
            rawPackets.add(McProtocolCodec.encode(packet));
        }

        byte[] frame = McTcpFrameCodec.encodeRequest(token, rawPackets);
        output.write(frame);
        output.flush();

        byte[] header = input.readNBytes(McTcpFrameCodec.FRAME_HEADER_SIZE);
        if (header.length != McTcpFrameCodec.FRAME_HEADER_SIZE) {
            throw new IOException("Unexpected EOF while reading MCTP header.");
        }

        ByteBuffer headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        int magic = headerBuffer.getInt();
        short version = headerBuffer.getShort();
        short kind = headerBuffer.getShort();
        int payloadLength = headerBuffer.getInt();

        if (magic != McTcpFrameCodec.FRAME_MAGIC) {
            throw new IOException("Unexpected MCTP magic: " + magic);
        }
        if (version != McTcpFrameCodec.FRAME_VERSION) {
            throw new IOException("Unsupported MCTP version: " + version);
        }
        if (kind != McTcpFrameCodec.RESPONSE_KIND) {
            throw new IOException("Unexpected MCTP frame kind: " + kind);
        }
        if (payloadLength < 0 || payloadLength > McTcpFrameCodec.MAX_FRAME_PAYLOAD_LENGTH) {
            throw new IOException("Invalid MCTP payload length: " + payloadLength);
        }

        byte[] payload = input.readNBytes(payloadLength);
        if (payload.length != payloadLength) {
            throw new IOException("Unexpected EOF while reading MCTP payload.");
        }

        McTcpFrameCodec.DecodedPayload decodedPayload = McTcpFrameCodec.decodePayload(payload);
        List<McApiPacket> decodedPackets = new ArrayList<>(decodedPayload.packets().size());
        for (byte[] packetBytes : decodedPayload.packets()) {
            decodedPackets.add(McProtocolCodec.decode(packetBytes));
        }
        return decodedPackets;
    }
}
