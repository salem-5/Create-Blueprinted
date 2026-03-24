package net.swzo.create_blueprinted;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.swzo.create_blueprinted.command.RenderSchemCommand;
import net.swzo.create_blueprinted.server.SchematicInfoServer;
import net.swzo.create_blueprinted.server.SchematicRenderServer;

public class CBClient {
    private static boolean hasJoinedWorld = false;
    private static int loadWaitTicks = 0;

    public static void onCtorClient(IEventBus modEventBus, IEventBus neoEventBus, ModContainer container) {
        neoEventBus.register(CBClient.class);
        SchematicRenderServer.start();
        SchematicInfoServer.start();
        System.out.println("Started HeadlessMC");
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        RenderSchemCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (hasJoinedWorld) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.getOverlay() == null && mc.level == null) {
            loadWaitTicks++;
            if (loadWaitTicks >= 60) {
                hasJoinedWorld = true;
                joinFirstWorld(mc);
            }
        } else if (mc.getOverlay() != null) {
            loadWaitTicks = 0;
        }
    }

    private static void joinFirstWorld(Minecraft mc) {
        try {
            LevelStorageSource storageSource = mc.getLevelSource();
            LevelStorageSource.LevelCandidates candidates = storageSource.findLevelCandidates();

            storageSource.loadLevelSummaries(candidates).thenAccept(levelSummaries -> {
                if (levelSummaries != null && !levelSummaries.isEmpty()) {
                    LevelSummary firstWorld = levelSummaries.get(0);
                    String levelId = firstWorld.getLevelId();

                    System.out.println("Auto-joining first world: " + levelId);
                    mc.execute(() -> {
                        mc.createWorldOpenFlows().openWorld(levelId, () -> mc.setScreen(null));
                    });
                } else {
                    System.out.println("No worlds found in the saves directory to auto-join.");
                }
            }).exceptionally(throwable -> {
                System.err.println("Failed to fetch level candidates for auto-join.");
                throwable.printStackTrace();
                return null;
            });
        } catch (Exception e) {
            System.err.println("Error while attempting to auto-join world.");
            e.printStackTrace();
        }
    }
}