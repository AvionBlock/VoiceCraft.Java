package team.avion.paper.scheduling;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import net.kyori.adventure.text.Component;
import team.avion.paper.VoiceCraftJava;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Single scheduling boundary for Bukkit, Paper and Folia.
 *
 * <p>Callers must choose the scope that owns the state they touch. Global tasks are
 * suitable for plugin state, entity tasks for players and entities, and async tasks
 * only for code that does not access Bukkit objects.</p>
 */
public final class PaperScheduler {
    private final FoliaLib foliaLib;

    public PaperScheduler(VoiceCraftJava plugin) {
        this.foliaLib = new FoliaLib(plugin);
    }

    public boolean isFolia() {
        return foliaLib.isFolia();
    }

    public boolean owns(Block block) {
        return foliaLib.getScheduler().isOwnedByCurrentRegion(block);
    }

    public org.bukkit.entity.Player player(UUID playerId) {
        return foliaLib.getScheduler().getPlayer(playerId);
    }

    public void broadcast(Component message) {
        foliaLib.getPlugin().getServer().getOnlinePlayers().forEach(player ->
                runAtEntity(player, () -> player.sendMessage(message)));
    }

    public void runGlobal(Runnable action) {
        foliaLib.getScheduler().runNextTick(ignored -> action.run());
    }

    public Task runGlobalTimer(Runnable action, long initialDelayTicks, long periodTicks) {
        return new Task(foliaLib.getScheduler().runTimer(action, initialDelayTicks, periodTicks));
    }

    public void runAsync(Runnable action) {
        foliaLib.getScheduler().runAsync(ignored -> action.run());
    }

    public CompletableFuture<?> runAtEntity(Entity entity, Runnable action) {
        Objects.requireNonNull(entity, "entity");
        return foliaLib.getScheduler().runAtEntity(entity, ignored -> action.run());
    }

    public Task runAtEntityTimer(Entity entity, Runnable action, Runnable retired,
            long initialDelayTicks, long periodTicks) {
        return new Task(foliaLib.getScheduler().runAtEntityTimer(
                entity, action, retired, initialDelayTicks, periodTicks));
    }

    public CompletableFuture<?> runAtLocation(Location location, Runnable action) {
        Objects.requireNonNull(location, "location");
        return foliaLib.getScheduler().runAtLocation(location, ignored -> action.run());
    }

    public CompletableFuture<Boolean> teleport(Entity entity, Location destination) {
        return foliaLib.getScheduler().teleportAsync(entity, destination);
    }

    public void shutdown() {
        foliaLib.getScheduler().cancelAllTasks();
    }

    /** Cancellable task handle independent of the active server implementation. */
    public static final class Task {
        private final WrappedTask delegate;

        private Task(WrappedTask delegate) {
            this.delegate = delegate;
        }

        public void cancel() {
            if (delegate != null && !delegate.isCancelled()) {
                delegate.cancel();
            }
        }

        public boolean isCancelled() {
            return delegate == null || delegate.isCancelled();
        }
    }
}
