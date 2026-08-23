package team.avion.fabric;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
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

    public VoiceCraftJava() {
        super(FMLPaths.CONFIGDIR.get().resolve("voicecraft-java"), new FabricLogger());
        instance = this;
        initializeRuntime();
        MinecraftForge.EVENT_BUS.register(this);
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
    public void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) serverTick(event.getServer());
    }

    @Override protected boolean isModLoaded(String modId) { return ModList.get().isLoaded(modId); }
    @Override protected void installMissingDependencies() {
        new ManagedModDependencyInstaller(this, "forge", FMLPaths.GAMEDIR.get().resolve("mods"),
                ModList.get()::isLoaded).installMissingEnabledDependencies();
    }
    @Override protected FabricVoiceCraftSessionManager createSessionManager() { return new FabricVoiceCraftSessionManager(this); }
    @Override protected FabricVoiceEndpointRegistry createVoiceEndpointRegistry() { return new FabricVoiceEndpointRegistry(this); }
    @Override protected FabricVoiceCraftProcessManager createProcessManager() { return new FabricVoiceCraftProcessManager(this); }
    @Override protected FabricPositionsTask createPositionsTask() { return new FabricPositionsTask(this); }
}
