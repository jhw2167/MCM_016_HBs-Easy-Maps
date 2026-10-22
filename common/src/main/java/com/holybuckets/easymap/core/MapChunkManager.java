package com.holybuckets.easymap.core;

import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.holybuckets.easymap.mixin.HoldingPlayerAccessor;
import com.holybuckets.foundation.GeneralConfig;
import com.holybuckets.foundation.HBUtil;
import com.holybuckets.foundation.core.ChunkExplorerManager;
import com.holybuckets.foundation.datastore.DataStore;
import com.holybuckets.foundation.event.EventRegistrar;
import com.holybuckets.foundation.event.custom.DatastoreSaveEvent;
import com.holybuckets.foundation.event.custom.PlayerHasItemEvent;
import com.holybuckets.foundation.event.custom.ServerTickEvent;
import com.holybuckets.foundation.event.custom.TickType;
import com.holybuckets.foundation.model.ManagedChunkUtility;
import com.holybuckets.easymap.Constants;
import com.holybuckets.easymap.LoggerProject;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.balm.api.event.ChunkLoadingEvent;
import net.blay09.mods.balm.api.event.EventPriority;
import net.blay09.mods.balm.api.event.LevelLoadingEvent;
import net.blay09.mods.balm.api.event.server.ServerStoppedEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapIndex;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class MapChunkManager {

    public static final byte MAP_SCALE = 4;
    public static final int BLOCKS_PER_PIXEL = 16;
    public static final int REGION_SPAN = BLOCKS_PER_PIXEL * 128;
    public static final String TICKET_ID = "hbs_easymap_region";
    private static final String DATA_KEY = "regionMaps";

    private static final Map<ServerLevel, LongSet> INITIALIZED_CHUNKS = new HashMap<>();
    private static final Map<Level, Map<ChunkPos, Integer>> REGIONS = new HashMap<>();
    private static final Map<String, Set<ChunkPos>> scoutedChunks = new HashMap<>();

    //** CONSTRUCTOR **/
    private MapChunkManager() {
    }

    //** UTILITY **/

    /** Minecraft vanilla algorithm **/
    public static int regionCenter(double block) {
        int span = 128 * (1 << MAP_SCALE);
        return Mth.floor((block + 64.0D) / (double) span) * span + span / 2 - 64;
    }

    public static BlockPos nearestRegionCenter(int blockX, int blockZ) {
        return new BlockPos(regionCenter(blockX), 0, regionCenter(blockZ));
    }

    public static boolean isRegionMap(@Nullable MapItemSavedData data, BlockPos center, ResourceKey<Level> dimension) {
        return data != null
            && data.scale == MAP_SCALE
            && data.centerX == center.getX()
            && data.centerZ == center.getZ()
            && data.dimension == dimension;
    }

    public static ChunkPos mapCenterChunk(MapItemSavedData data) {
        return new ChunkPos(new BlockPos(data.centerX, 0, data.centerZ));
    }

    public static void addScoutedChunk(ServerPlayer player, MapItemSavedData data) {
        if (player == null || data == null) return;
        String pId = HBUtil.PlayerUtil.getId(player);
        ChunkPos center = mapCenterChunk(data);
        scoutedChunks.computeIfAbsent(pId, p -> new HashSet<>()).add(center);
    }

    public static boolean isScouted(ServerPlayer player, ChunkPos center) {
        Set<ChunkPos> centers = scoutedChunks.get(HBUtil.PlayerUtil.getId(player));
        return centers != null && centers.contains(center);
    }

    private static Map<ChunkPos, Integer> regions(ServerLevel level) {
        return REGIONS.computeIfAbsent(level, k -> new HashMap<>());
    }

    @Nullable
    public static Integer getRegionMapId(ServerLevel level, int blockX, int blockZ) {
        if (level == null) return null;
        BlockPos center = nearestRegionCenter(blockX, blockZ);
        ChunkPos key = new ChunkPos(center);
        Integer mapId = regions(level).get(key);
        if (mapId != null) {
            if (isRegionMap(MapItem.getSavedData(mapId, level), center, level.dimension()))
                return mapId;
        }

        MapItemSavedData data = MapItemSavedData.createFresh(blockX, blockZ, MAP_SCALE, true, true, level.dimension());
        if (!isRegionMap(data, center, level.dimension())) {
            return null;
        }

        int newId = level.getFreeMapId();
        level.setMapData(MapItem.makeKey(newId), data);
        data.setDirty();
        regions(level).put(new ChunkPos(center), newId);

        LoggerProject.logDebug("016022", "Created region map " + newId + " at key " + key);
        return newId;
    }


    @Nullable
    public static MapItemSavedData getRegionMap(ServerLevel level, int blockX, int blockZ) {
        Integer mapId = getRegionMapId(level, blockX, blockZ);
        return mapId == null ? null : MapItem.getSavedData(mapId, level);
    }


    //** EVENTS **/
    public static void init(EventRegistrar reg) {
        Balm.getEvents().onEvent(LevelLoadingEvent.Load.class, MapChunkManager::onLevelLoad, EventPriority.Lowest);
        //reg.registerOnLevelLoad(MapChunkManager::onLevelLoad, EventPriority.Lowest);
        reg.registerOnChunkLoad(MapChunkManager::handleChunkLoad);
        reg.registerOnServerTick(TickType.ON_120_TICKS, MapChunkManager::on120Ticks);
        reg.registerOnServerStopped(MapChunkManager::onServerStopped);

        reg.registerOnPlayerMatchesItem(MapChunkManager::playerHasMap, MapChunkManager::onPlayerHasItem);
    }

    private static boolean playerHasMap(ItemStack item) {
        return item!=null && item.getItem() instanceof MapItem;
    }


    private static void handleChunkLoad(ChunkLoadingEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        ChunkAccess chunk = event.getChunk();
        if (chunk == null) return;
        if(INITIALIZED_CHUNKS.get(level)==null) return;
        if(INITIALIZED_CHUNKS.get(level).contains(HBUtil.ChunkUtil.getChunkPos1DMap(chunk.getPos()) )) return;

        INITIALIZED_CHUNKS.get(level).add(HBUtil.ChunkUtil.getChunkPos1DMap(chunk.getPos()));
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

    //playerhasMap, checkMap, updateMap, markDirty, updateColors
    private static final Map<Player, ItemStack> PLAYERS_HAVE_MAP = new HashMap<>();
    public static void onPlayerHasItem(PlayerHasItemEvent event) {
        PLAYERS_HAVE_MAP.put(event.getPlayer(), event.getItemStack());
    }

    private static void on120Ticks(ServerTickEvent event) {
        for (ServerPlayer player : HBUtil.PlayerUtil.getAllPlayers()) {
            if (player == null || !(player.level() instanceof ServerLevel level)) continue;
            preloadNearestRegionCenter(level, player);
        }

        //for all players with maps, check if they have scouted the region, if so, update the map
        for (Player player : PLAYERS_HAVE_MAP.keySet()) {
            if (player instanceof ServerPlayer p)
                refreshHeldMaps(p, PLAYERS_HAVE_MAP.get(player));
        }
    }

    private static void onLevelLoad(LevelLoadingEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        LongSet initialized = ManagedChunkUtility.getInstance(level).getInitializedChunks();

        //Load all possible Maps
        INITIALIZED_CHUNKS.computeIfAbsent(level, k -> new LongOpenHashSet()).addAll(initialized);
        MapIndex indices = level.getDataStorage().get(MapIndex::load, "idcounts");
        if(indices==null) return;
        CompoundTag retriever = new CompoundTag();
        indices.save(retriever);

        for(String k : retriever.getAllKeys()) {
            int i = retriever.getInt(k);
            MapItemSavedData data = level.getMapData("map_" + i);
            if(isRegionMap(data, nearestRegionCenter(data.centerX,data.centerZ),level.dimension())) {
                ChunkPos key = new ChunkPos(new BlockPos(data.centerX,0,data.centerZ));
                regions(level).put(key,i);
            }
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        REGIONS.clear();
        scoutedChunks.clear();
    }


    //** STATIC CORE **//
    public static void preloadNearestRegionCenter(ServerLevel level, ServerPlayer player) {
        if (level == null || player == null) return;
        BlockPos bp = player.blockPosition();
        ChunkPos center = new ChunkPos(nearestRegionCenter(bp.getX(), bp.getZ()));

        if (ManagedChunkUtility.getInstance(level).isChunkInitialized(center.x, center.z)) return;
        ChunkExplorerManager.submitPriorityChunk(level, new ChunkPos(center.x, center.z));
    }

    public static void submitRegionChunks(ServerLevel level, MapItemSavedData data) {
        if (level == null || data == null) return;

        ManagedChunkUtility util = ManagedChunkUtility.getInstance(level);
        int mapChunkRadius = REGION_SPAN / 2;
        ChunkPos origin = HBUtil.ChunkUtil.getChunkPos(new BlockPos(data.centerX, 0, data.centerZ));

        //iterate over x and z to size mapChunkRadius
        for (int dx = -mapChunkRadius; dx <= mapChunkRadius; dx += 8) {
            for (int dz = -mapChunkRadius; dz <= mapChunkRadius; dz += 8) {
                int cx = origin.x + dx;
                int cz = origin.z + dz;
                if (util.isChunkInitialized(cx, cz)) continue;
                ChunkExplorerManager.submitPriorityChunk(level, new ChunkPos(cx, cz));
            }
        }

    }


    public static int refreshHeldMaps(ServerPlayer player, ItemStack map) {
        MapItemSavedData data = MapItem.getSavedData(map, player.level());
        if (data == null) return 0;
        if (!isScouted(player, mapCenterChunk(data))) return 0;
        data.tickCarriedBy(player, map);
        markMapFullyDirty(MapItem.getMapId(map), data, player);
        MapRevealerManager.revealStructures( (ServerLevel) player.level(), map);
        return 1;
    }




    //** PIXEL UTILITY **//

    public static void markMapFullyDirty(int mapId, MapItemSavedData data, ServerPlayer player) {
        if (data == null || player == null) return;

        HoldingPlayerAccessor holder = (HoldingPlayerAccessor) data.getHoldingPlayer(player);
        holder.easymapSetMinDirtyX(0);
        holder.easymapSetMinDirtyY(0);
        holder.easymapSetMaxDirtyX(127);
        holder.easymapSetMaxDirtyY(127);
        holder.easymapSetDirtyData(true);
        holder.easymapSetDirtyDecorations(true);
        player.connection.send(data.getUpdatePacket(mapId, player));
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


}
