package com.holybuckets.easymap.config;

import com.holybuckets.easymap.Constants;
import net.blay09.mods.balm.api.config.reflection.Comment;
import net.blay09.mods.balm.api.config.reflection.Config;

@Config(value = Constants.MOD_ID, type = "client")
public class EasyMapsClientConfig {

    @Comment("Side of the screen the pinned map and compass information is drawn on (LEFT, RIGHT)")
    public OverlayAlignment overlayAlignment = OverlayAlignment.RIGHT;

    @Comment("Width and height of the pinned map overlay in GUI pixels, 32 to 256")
    public int overlayMapSize = 64;

    @Comment("Scale of the overlay text, 1.0 is vanilla text size, 0.5 to 3.0")
    public double overlayTextScale = 1.0;

}
