package net.swzo.create_blueprinted;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(CB.MODID)
public class CB {
    public static final String MODID = "create_blueprinted";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CB() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CBClient.onCtorClient(modEventBus, MinecraftForge.EVENT_BUS));
    }
}
