package team.avion.fabric.proxy;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import team.avion.fabric.VoiceCraftJava;
import team.avion.mod.ModProxyBridge;
import team.avion.proxy.ProxyMessageCodec;
import team.avion.proxy.ProxyPlayerSnapshot;

/** Minecraft 26.3 plugin channel carrying Fabric backend state to Velocity. */
public final class FabricProxyBridge implements ModProxyBridge {
    private static final int MAX_MESSAGE_BYTES = 2048;
    private static final CustomPacketPayload.Type<ProxyPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse(ProxyMessageCodec.CHANNEL_NAME));
    private static final StreamCodec<RegistryFriendlyByteBuf, ProxyPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeBytes(payload.bytes()),
            buffer -> {
                int length = buffer.readableBytes();
                if (length < 1 || length > MAX_MESSAGE_BYTES) {
                    throw new IllegalArgumentException("Invalid VoiceCraft proxy message length: " + length);
                }
                byte[] bytes = new byte[length];
                buffer.readBytes(bytes);
                return new ProxyPayload(bytes);
            });

    private final VoiceCraftJava plugin;

    public FabricProxyBridge(VoiceCraftJava plugin) {
        this.plugin = plugin;
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) -> {
            try {
                if (!ProxyMessageCodec.BIND_SYNC.equals(ProxyMessageCodec.peekType(payload.bytes()))) {
                    return;
                }
                ProxyMessageCodec.BindSync sync = ProxyMessageCodec.decodeBindSync(payload.bytes());
                plugin.syncPlayerBind(sync.playerName(), sync.bound());
            } catch (IllegalArgumentException exception) {
                plugin.Logger.warn("Rejected malformed VoiceCraft proxy message: " + exception.getMessage());
            }
        });
    }

    @Override
    public boolean sendSnapshot(ServerPlayer player, ProxyPlayerSnapshot snapshot) {
        return send(player, ProxyMessageCodec.encodeSnapshot(snapshot));
    }

    @Override
    public boolean sendBindRequest(ServerPlayer player, int bindingKey) {
        return send(player, ProxyMessageCodec.encodeBindRequest(
                player.getUUID().toString(), player.getName().getString(), bindingKey));
    }

    @Override
    public boolean sendUnbindRequest(ServerPlayer player) {
        return send(player, ProxyMessageCodec.encodeUnbindRequest(
                player.getUUID().toString(), player.getName().getString()));
    }

    private boolean send(ServerPlayer player, byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_MESSAGE_BYTES) {
            return false;
        }
        try {
            // The receiver is Velocity, so a player-side channel advertisement is not required.
            player.connection.send(new ClientboundCustomPayloadPacket(new ProxyPayload(bytes)));
            return true;
        } catch (RuntimeException exception) {
            plugin.Logger.warn("Failed to send VoiceCraft proxy message: " + exception.getMessage());
            return false;
        }
    }

    private record ProxyPayload(byte[] bytes) implements CustomPacketPayload {
        @Override
        public Type<ProxyPayload> type() {
            return TYPE;
        }
    }
}
