package team.avion.mod;

import net.minecraft.server.level.ServerPlayer;
import team.avion.proxy.ProxyPlayerSnapshot;

/** Backend-to-proxy messages shared by modloader implementations. */
public interface ModProxyBridge {
    boolean sendSnapshot(ServerPlayer player, ProxyPlayerSnapshot snapshot);

    boolean sendBindRequest(ServerPlayer player, int bindingKey);

    boolean sendUnbindRequest(ServerPlayer player);
}
