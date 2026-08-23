package team.avion.fabric;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import team.avion.fabric.tasks.FabricPositionsTask;
import team.avion.fabric.utils.FabricLogger;
import team.avion.fabric.voicecraft.FabricVoiceCraftProcessManager;
import team.avion.fabric.voicecraft.FabricVoiceCraftSessionManager;
import team.avion.fabric.voicecraft.FabricVoiceEndpointRegistry;
import team.avion.mod.AbstractVoiceCraftMod;
import team.avion.mod.ManagedModDependencyInstaller;
import team.avion.mod.ModCommandRegistrar;

import java.nio.file.Path;

/** Fabric lifecycle and command binding for the shared mod runtime. */
public final class VoiceCraftJava extends AbstractVoiceCraftMod implements ModInitializer {
    private static VoiceCraftJava instance;

    public VoiceCraftJava() {
        super(dataFolder(), new FabricLogger());
    }

    public static VoiceCraftJava getInstance() {
        return instance;
    }

    private static Path dataFolder() {
        return FabricLoader.getInstance().getConfigDir().resolve("voicecraft-java");
    }

    @Override
    public void onInitialize() {
        instance = this;
        initializeRuntime();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ModCommandRegistrar.register(dispatcher, this,
                        (source, permission, level) -> Permissions.check(source, permission, level)));
        ServerLifecycleEvents.SERVER_STARTED.register(this::serverStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(ignored -> serverStopping());
        ServerTickEvents.END_SERVER_TICK.register(this::serverTick);
    }

    @Override
    protected boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    protected void installMissingDependencies() {
        new ManagedModDependencyInstaller(this, "fabric", FabricLoader.getInstance().getGameDir().resolve("mods"),
                FabricLoader.getInstance()::isModLoaded).installMissingEnabledDependencies();
    }

    @Override
    protected FabricVoiceCraftSessionManager createSessionManager() {
        return new FabricVoiceCraftSessionManager(this);
    }

    @Override
    protected FabricVoiceEndpointRegistry createVoiceEndpointRegistry() {
        return new FabricVoiceEndpointRegistry(this);
    }

    @Override
    protected FabricVoiceCraftProcessManager createProcessManager() {
        return new FabricVoiceCraftProcessManager(this);
    }

    @Override
    protected FabricPositionsTask createPositionsTask() {
        return new FabricPositionsTask(this);
    }
}
