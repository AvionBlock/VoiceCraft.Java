package team.avion.fabric;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import team.avion.fabric.tasks.FabricPositionsTask;
import team.avion.fabric.utils.FabricLogger;
import team.avion.fabric.voicecraft.FabricVoiceCraftProcessManager;
import team.avion.fabric.voicecraft.FabricVoiceCraftSessionManager;
import team.avion.fabric.voicecraft.FabricVoiceEndpointRegistry;
import team.avion.mod.AbstractVoiceCraftMod;
import team.avion.mod.ManagedModDependencyInstaller;
import team.avion.mod.ModCommandRegistrar;
import team.avion.mod.ModPermissions;

@Mod("voicecraft_java")
public final class VoiceCraftJava extends AbstractVoiceCraftMod {
    private static VoiceCraftJava instance;

    public VoiceCraftJava(IEventBus modEventBus) {
        super(FMLPaths.CONFIGDIR.get().resolve("voicecraft-java"), new FabricLogger());
        instance = this;
        initializeRuntime();
        NeoForge.EVENT_BUS.register(this);
    }

    public static VoiceCraftJava getInstance() { return instance; }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent event) { serverStarted(event.getServer()); }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent event) { serverStopping(); }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent event) {
        ModCommandRegistrar.register(event.getDispatcher(), this,
                (source, permission, level) -> ModPermissions.isOperator(source, level));
    }

    @SubscribeEvent
    public void onTick(ServerTickEvent.Post event) { serverTick(event.getServer()); }

    @Override protected boolean isModLoaded(String modId) { return ModList.get().isLoaded(modId); }
    @Override protected void installMissingDependencies() {
        new ManagedModDependencyInstaller(this, "neoforge", FMLPaths.GAMEDIR.get().resolve("mods"),
                ModList.get()::isLoaded).installMissingEnabledDependencies();
    }
    @Override protected FabricVoiceCraftSessionManager createSessionManager() { return new FabricVoiceCraftSessionManager(this); }
    @Override protected FabricVoiceEndpointRegistry createVoiceEndpointRegistry() { return new FabricVoiceEndpointRegistry(this); }
    @Override protected FabricVoiceCraftProcessManager createProcessManager() { return new FabricVoiceCraftProcessManager(this); }
    @Override protected FabricPositionsTask createPositionsTask() { return new FabricPositionsTask(this); }
}
