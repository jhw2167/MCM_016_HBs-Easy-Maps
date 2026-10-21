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

    @Comment("Effective scale applied for each stored vanilla map scale 0-4. Values above 4 exceed vanilla range and cost more to fill")
    @NestedType(Integer.class)
    public List<Integer> mapScaleTable = new ArrayList<>(Arrays.asList(4, 4, 4, 4, 4));

    @Comment("Hard ceiling on effective map scale regardless of mapScaleTable")
    public int maxEffectiveMapScale = 4;

    @Comment("Scale used when a cartography table reveals a blank map")
    public int revealDefaultScale = 4;

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

    @Comment("Submit map edge chunks to the background chunk loader when a reveal is requested")
    public boolean primeEdgeChunks = true;

    @Comment("Spacing in chunks between sampled map chunks when priming the background loader")
    public int primeChunkStride = 8;
}
