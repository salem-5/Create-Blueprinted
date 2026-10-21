package net.swzo.create_blueprinted;

import com.llamalad7.mixinextras.sugar.Share;
import com.mojang.logging.LogUtils;
import net.createmod.catnip.config.ui.BaseConfigScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.swzo.create_blueprinted.api.ShareProviderRegistry;
import net.swzo.create_blueprinted.api.test.BeanProvider;
import net.swzo.create_blueprinted.api.test.FoxProvider;
import net.swzo.create_blueprinted.command.CBSchematicCommands;
import net.swzo.create_blueprinted.util.DebugTimer;
import net.swzo.create_blueprinted.util.UIHelpers;
import org.slf4j.Logger;

import static net.swzo.create_blueprinted.CreateBlueprintedConfig.CONFIG;

@Mod(value = CreateBlueprinted.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(Dist.CLIENT)
public class CreateBlueprinted {

    public static final String MOD_ID = "create_blueprinted";
    public static final String CONFIG_ID = MOD_ID.replace('_', '-');
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final DebugTimer DEBUG_TIMER = new DebugTimer();

    private static final BeanProvider beanProvider = new BeanProvider();
    private static final FoxProvider foxProvider = new FoxProvider();

    public CreateBlueprinted(IEventBus __, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, State.SPEC, CONFIG_ID + "-state.toml");
        container.registerConfig(ModConfig.Type.CLIENT, CONFIG.specification, CreateBlueprintedConfig.getFullConfigName());
        container.registerExtensionPoint(IConfigScreenFactory.class, (_container, screen)
                -> new BaseConfigScreen(screen, _container.getModId()));

        // Only use these for testing...
        // ShareProviderRegistry.register(beanProvider);
        // ShareProviderRegistry.register(foxProvider);
    }

    public static boolean isModLoaded(String modId) {
        return LoadingModList.get().getModFileById(modId) != null;
    }

    public static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    public static ResourceLocation rl(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    public static MutableComponent translatable(String path, Object... args) {
        return Component.translatable(MOD_ID + "." + path, args);
    }

    public static MutableComponent translatableError(String path, Object... args) {
        return Component.translatable(MOD_ID + ".error." + path, args).withColor(UIHelpers.ERROR_DARKER);
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent e) {
        CBSchematicCommands.register(e.getDispatcher());
    }

    @SubscribeEvent
    public static void onGameLoad(FMLLoadCompleteEvent e) {
        ShareProviderRegistry.getActiveShareProvider().ifPresent(provider -> {
            if (!provider.id().toString().equals(State.activeShareProvider.get())) {
                State.activeShareProvider.set(provider.id().toString());
                State.activeShareProvider.save();
            }
        });
    }

    public static class State {

        private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

        public static final ModConfigSpec.ConfigValue<String> activeShareProvider = BUILDER
                .comment("Resource location of the share provider that is currently active.")
                .define("activeShareProvider", "", provider ->
                        provider instanceof String providerString &&
                                ResourceLocation.tryParse(providerString) != null);

        static ModConfigSpec SPEC = BUILDER.build();
    }
}
