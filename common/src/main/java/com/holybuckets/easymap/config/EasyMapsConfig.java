package com.holybuckets.easymap.config;

import com.holybuckets.easymap.Constants;
import net.blay09.mods.balm.api.config.reflection.Comment;
import net.blay09.mods.balm.api.config.reflection.Config;
import net.blay09.mods.balm.api.config.reflection.NestedType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Config(Constants.MOD_ID)
public class EasyMapsConfig {

    @Comment("devMode==true logs structure reveal and chunk priming results")
    public boolean devMode = false;

    @Comment("Structure ids a cartography table may reveal on a map. Namespaced ids, minecraft namespace assumed when omitted")
    @NestedType(String.class)
    public List<String> revealableStructures = new ArrayList<>(Arrays.asList(
        "minecraft:village_plains",
        "minecraft:village_desert",
        "minecraft:village_savanna",
        "minecraft:village_snowy",
        "minecraft:village_taiga",
        "minecraft:pillager_outpost",
        "minecraft:desert_pyramid",
        "minecraft:jungle_pyramid",
        "minecraft:swamp_hut",
        "minecraft:igloo",
        "minecraft:monument",
        "minecraft:mansion",
        "minecraft:ancient_city",
        "minecraft:trail_ruins"
    ));

    @Comment("Maximum structure icons placed on a single map. Nearest to the map center win")
    public int maxIconsPerMap = 24;

    @Comment("Fill the map terrain from the biome source when revealed at a cartography table")
    public boolean revealFillsTerrain = true;

}
