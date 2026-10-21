package com.holybuckets.easymap.core;

import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.holybuckets.foundation.GeneralConfig;
import com.holybuckets.foundation.HBUtil;
import com.holybuckets.foundation.core.ChunkExplorerManager;
import com.holybuckets.foundation.datastore.DataStore;
import com.holybuckets.foundation.event.EventRegistrar;
import com.holybuckets.foundation.event.custom.DatastoreSaveEvent;
import com.holybuckets.foundation.event.custom.ServerTickEvent;
import com.holybuckets.foundation.event.custom.TickType;
import com.holybuckets.foundation.model.ManagedChunkUtility;
import com.holybuckets.easymap.Constants;
import com.holybuckets.easymap.LoggerProject;
import com.holybuckets.easymap.config.ModConfig;
import net.blay09.mods.balm.api.event.ChunkLoadingEvent;
import net.blay09.mods.balm.api.event.LevelLoadingEvent;
import net.blay09.mods.balm.api.event.server.ServerStoppedEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

public class MapChunkManager {

    public static final byte MAP_SCALE = 4;
    public static final int BLOCKS_PER_PIXEL = 16;
    public static final int REGION_SPAN = BLOCKS_PER_PIXEL * 128;
    public static final String TICKET_ID = "hbs_easymap_region";
    private static final String DATA_KEY = "regionMaps";

    private static final Map<ResourceKey<Level>, Map<Long, Integer>> REGIONS = new HashMap<>();

    //** CONSTRUCTOR **/
    private MapChunkManager() {
    }

    //** UTILITY **/
    public static int regionCenter(int block) {
        return Math.floorDiv(block + 64, REGION_SPAN) * REGION_SPAN + REGION_SPAN / 2 - 64;
    }

    public static ChunkPos regionCenterChunk(int blockX, int blockZ) {
        return new ChunkPos(regionCenter(blockX) >> 4, regionCenter(blockZ) >> 4);
    }

    public static long regionKey(int blockX, int blockZ) {
        return ChunkPos.asLong(regionCenter(blockX) >> 4, regionCenter(blockZ) >> 4);
    }

    private static Map<Long, Integer> regions(ServerLevel level) {
        return REGIONS.computeIfAbsent(level.dimension(), k -> new HashMap<>());
    }

    @Nullable
    public static Integer getRegionMapId(ServerLevel level, int blockX, int blockZ) {
        if (level == null) return null;
        long key = regionKey(blockX, blockZ);
        Integer mapId = regions(level).get(key);
        if (mapId != null && MapItem.getSavedData(mapId, level) != null) return mapId;

        int newId = level.getFreeMapId();
        MapItemSavedData data = MapItemSavedData.createFresh(
            regionCenter(blockX), regionCenter(blockZ), MAP_SCALE, true, true, level.dimension());
        level.setMapData(MapItem.makeKey(newId), data);
        regions(level).put(key, newId);

        LoggerProject.logDebug("016022", "Created region map " + newId + " at key " + key);
        return newId;
    }



    @Nullable
    public static MapItemSavedData getRegionMap(ServerLevel level, int blockX, int blockZ) {
        Integer mapId = getRegionMapId(level, blockX, blockZ);
        return mapId == null ? null : MapItem.getSavedData(mapId, level);
    }

    public static void init(EventRegistrar reg) {
        reg.registerOnLevelLoad(MapChunkManager::onLevelLoad);
        reg.registerOnDataSave(MapChunkManager::onDataSave);
        reg.registerOnChunkLoad(MapChunkManager::handleChunkLoad);
        reg.registerOnServerTick(TickType.ON_20_TICKS, MapChunkManager::on20Ticks);
        reg.registerOnServerStopped(MapChunkManager::onServerStopped);
    }


    //** EVENTS **/
    private static void handleChunkLoad(ChunkLoadingEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        ChunkAccess chunk = event.getChunk();
        if (chunk == null) return;

        int blockX = chunk.getPos().getMinBlockX();
        int blockZ = chunk.getPos().getMinBlockZ();

        MapItemSavedData data = getRegionMap(level, blockX, blockZ);
        if (data == null) return;

        int px = Math.floorDiv(blockX, BLOCKS_PER_PIXEL) - (Math.floorDiv(data.centerX, BLOCKS_PER_PIXEL) - 64);
        int pz = Math.floorDiv(blockZ, BLOCKS_PER_PIXEL) - (Math.floorDiv(data.centerZ, BLOCKS_PER_PIXEL) - 64);
        if (px < 0 || px > 127 || pz < 0 || pz > 127) return;
        if (data.colors[px + pz * 128] != 0) return;

        capturePixel(level, chunk, data, px, pz, blockX, blockZ);
    }

    private static void capturePixel(ServerLevel level, ChunkAccess chunk, MapItemSavedData data,
                                     int px, int pz, int blockX, int blockZ) {
        int minY = level.getMinBuildHeight();
        boolean hasCeiling = level.dimensionType().hasCeiling();

        Multiset<MapColor> counts = LinkedHashMultiset.create();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int fluidDepth = 0;
        int samples = 0;

        for (int sx = 0; sx < BLOCKS_PER_PIXEL; sx++) {
            for (int sz = 0; sz < BLOCKS_PER_PIXEL; sz++) {
                int worldX = blockX + sx;
                int worldZ = blockZ + sz;

                if (hasCeiling) {
                    int noise = worldX + worldZ * 231871;
                    noise = noise * noise * 31287121 + noise * 11;
                    counts.add((noise >> 20 & 1) == 0
                        ? Blocks.DIRT.defaultBlockState().getMapColor(level, BlockPos.ZERO)
                        : Blocks.STONE.defaultBlockState().getMapColor(level, BlockPos.ZERO));
                    samples++;
                    continue;
                }

                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ) + 1;
                BlockState state;

                if (y <= minY + 1) {
                    state = Blocks.BEDROCK.defaultBlockState();
                } else {
                    do {
                        y--;
                        cursor.set(worldX, y, worldZ);
                        state = chunk.getBlockState(cursor);
                    } while (state.getMapColor(level, cursor) == MapColor.NONE && y > minY);

                    if (y > minY && !state.getFluidState().isEmpty()) {
                        int depthY = y - 1;
                        BlockState below;
                        do {
                            cursor.set(worldX, depthY--, worldZ);
                            below = chunk.getBlockState(cursor);
                            fluidDepth++;
                        } while (depthY > minY && !below.getFluidState().isEmpty());
                    }
                }

                counts.add(state.getMapColor(level, cursor));
                samples++;
            }
        }

        if (samples == 0) return;
        MapColor color = Iterables.getFirst(Multisets.copyHighestCountFirst(counts), MapColor.NONE);
        if (color == MapColor.NONE) return;

        MapColor.Brightness brightness = MapColor.Brightness.NORMAL;
        if (color == MapColor.WATER) {
            double shade = (double) fluidDepth / samples * 0.1 + ((px + pz) & 1) * 0.2;
            if (shade < 0.5) brightness = MapColor.Brightness.HIGH;
            else if (shade > 0.9) brightness = MapColor.Brightness.LOW;
        }

        data.setColor(px, pz, color.getPackedId(brightness));
    }

    private static void on20Ticks(ServerTickEvent event) {
        for (ServerPlayer player : HBUtil.PlayerUtil.getAllPlayers()) {
            if (player == null || !(player.level() instanceof ServerLevel level)) continue;
            preloadNearestRegionCenter(level, player);
        }
    }

    public static void preloadNearestRegionCenter(ServerLevel level, ServerPlayer player) {
        if (level == null || player == null) return;

        ChunkPos center = regionCenterChunk(player.blockPosition().getX(), player.blockPosition().getZ());
        if (ManagedChunkUtility.getInstance(level).isChunkInitialized(center.x, center.z)) return;
        ChunkExplorerManager.submitPriorityChunk(level, new ChunkPos(center.x, center.z));
    }

    private static void onLevelLoad(LevelLoadingEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        DataStore ds = GeneralConfig.getInstance().getDataStore();
        if (ds == null) return;

        JsonElement root = ds.getOrCreateLevelSaveData(Constants.MOD_ID, level).get(DATA_KEY);
        if (root == null || !root.isJsonObject()) return;

        Map<Long, Integer> byKey = regions(level);
        byKey.clear();
        JsonObject obj = root.getAsJsonObject();
        for (String key : obj.keySet()) {
            byKey.put(Long.parseLong(key), obj.get(key).getAsInt());
        }
    }

    private static void onDataSave(DatastoreSaveEvent event) {
        DataStore ds = event.getDataStore();
        if (ds == null) return;

        for (Map.Entry<ResourceKey<Level>, Map<Long, Integer>> entry : REGIONS.entrySet()) {
            ServerLevel level = GeneralConfig.getInstance().getServer().getLevel(entry.getKey());
            if (level == null) continue;

            JsonObject obj = new JsonObject();
            entry.getValue().forEach((key, mapId) -> obj.addProperty(String.valueOf(key), mapId));
            ds.getOrCreateLevelSaveData(Constants.MOD_ID, level).addProperty(DATA_KEY, obj);
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        REGIONS.clear();
    }
}
