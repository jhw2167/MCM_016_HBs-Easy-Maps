package com.holybuckets.easymap;


import com.holybuckets.foundation.event.EventRegistrar;
import com.holybuckets.easymap.config.EasyMapsConfig;
import com.holybuckets.easymap.config.ModConfig;
import com.holybuckets.easymap.core.MapChunkManager;
import com.holybuckets.easymap.core.MapRevealerManager;
import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.balm.api.event.EventPriority;
import net.blay09.mods.balm.api.event.server.ServerStartingEvent;

/**
 * Main instance of the mod, initialize this class statically via commonClass
 * This class will init all major Manager instances and events for the mod
 */
public class EasyMapsMain {
    private static boolean DEV_MODE = false;;
    private static EasyMapsConfig CONFIG;
    public static EasyMapsMain INSTANCE;

    public EasyMapsMain()
    {
        super();
        INSTANCE = this;
        init();
        // LoggerProject.logInit( "001000", this.getClass().getName() ); // Uncomment if you have a logging system in place
    }

    private void init()
    {

        /*
        Proxy for external APIs which are platform dependent
        this.portalApi = (PortalApi) Balm.platformProxy()
            .withFabric("com.holybuckets.challengetemple.externalapi.FabricPortalApi")
            .withForge("com.holybuckets.challengetemple.externalapi.ForgePortalApi")
            .build();
            */

        //Events
        EventRegistrar registrar = EventRegistrar.getInstance();
        ModConfig.init(registrar);
        MapChunkManager.init(registrar);
        MapRevealerManager.init(registrar);

        //register local events
        registrar.registerOnBeforeServerStarted(this::onServerStarting);

    }

    private void onServerStarting(ServerStartingEvent e) {
        CONFIG = Balm.getConfig().getActiveConfig(EasyMapsConfig.class);
        //this.DEV_MODE = CONFIG.devMode;
        this.DEV_MODE = false;
    }


}
