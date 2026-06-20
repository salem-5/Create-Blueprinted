package net.swzo.create_blueprinted;

import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.swzo.create_blueprinted.command.RenderSchemCommand;
import net.swzo.create_blueprinted.render.RenderProgress;

public class CBClient {
    public static void onCtorClient(IEventBus modEventBus, IEventBus forgeEventBus) {
        forgeEventBus.register(CBClient.class);
        forgeEventBus.register(RenderProgress.class);
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        RenderSchemCommand.register(event.getDispatcher());
    }
}