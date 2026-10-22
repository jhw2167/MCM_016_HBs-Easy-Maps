package com.holybuckets.easymap.client;

import com.holybuckets.easymap.config.EasyMapsClientConfig;
import com.holybuckets.foundation.client.ClientBalmEventRegister;
import com.holybuckets.foundation.client.ClientEventRegistrar;
import net.blay09.mods.balm.api.Balm;


public class CommonClassClient {

    public static void initClient() {
        ClientEventRegistrar registrar = ClientEventRegistrar.getInstance();
        Balm.getConfig().registerConfig(EasyMapsClientConfig.class);
        MapOverlayClient.init(registrar);
        ClientBalmEventRegister.registerEvents();
        //ModItems.clientInitialize();
    }

    /**
     * Description: Run sample tests methods
     */
    public static void sample()
    {

    }


}