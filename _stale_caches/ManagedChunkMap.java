package com.holybuckets.easymap.core;

import com.holybuckets.foundation.HBUtil.ChunkUtil;
import com.holybuckets.foundation.model.ManagedChunk;
import com.holybuckets.foundation.modelInterface.IMangedChunkData;
import com.holybuckets.easymap.LoggerProject;
import net.blay09.mods.balm.api.event.ChunkLoadingEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import javax.annotation.Nullable;
import java.util.Arrays;

public class ManagedChunkMap implements IMangedChunkData {

    private static final String CLASS_ID = "016";
    public static final String DEFAULT_ID = "DEFAULT";
    public static final int IMAGE_SIZE = 128 * 128;
    public static final int NO_MAP_ID = -1;

    public static final int SECTION_CHUNKS = 128;
    public static final byte MAP_SCALE = 4;

    private ServerLevel level;
    private String id;
    private ChunkPos pos;
    private int mapId = NO_MAP_ID;

    private MapItemSavedData data;
    private float[] heights;

    private ManagedChunkMap() {
        this.id = DEFAULT_ID;
    }

    public ManagedChunkMap(LevelAccessor level, String id) {
        this();
        setLevel(level);
        setId(id);
    }

    public static void registerManagedChunkData() {
        ManagedChunk.registerManagedChunkData(ManagedChunkMap.class, ManagedChunkMap::new);
    }

    public static boolean isSectionCenter(ChunkPos pos) {
        return pos != null
            && Math.floorMod(pos.x, SECTION_CHUNKS) == 0
            && Math.floorMod(pos.z, SECTION_CHUNKS) == 0;
    }

    public static ChunkPos sectionCenterOf(ChunkPos pos) {
        if (pos == null) return null;
        int x = Math.floorDiv(pos.x, SECTION_CHUNKS) * SECTION_CHUNKS;
        int z = Math.floorDiv(pos.z, SECTION_CHUNKS) * SECTION_CHUNKS;
        return new ChunkPos(x, z);
    }

    public ServerLevel getLevel() {
        return this.level;
    }

    public String getId() {
        return this.id;
    }

    public ChunkPos getPos() {
        return this.pos;
    }

    public int getMapId() {
        return this.mapId;
    }

    public boolean hasMapId() {
        return this.mapId != NO_MAP_ID;
    }

    public int centerBlockX() {
        return this.pos == null ? 0 : this.pos.getMiddleBlockX();
    }

    public int centerBlockZ() {
        return this.pos == null ? 0 : this.pos.getMiddleBlockZ();
    }

    @Nullable
    public MapItemSavedData getData() {
        if (this.data != null) return this.data;
        if (this.level == null) return null;

        if (hasMapId()) {
            this.data = MapItem.getSavedData(this.mapId, this.level);
            if (this.data != null) return this.data;
        }

        return createData();
    }

    @Nullable
    private MapItemSavedData createData() {
        if (this.level == null || this.pos == null) return null;

        this.mapId = this.level.getFreeMapId();
        this.data = MapItemSavedData.createFresh(
            centerBlockX(), centerBlockZ(), MAP_SCALE, true, true, this.level.dimension());
        this.level.setMapData(MapItem.makeKey(this.mapId), this.data);

        LoggerProject.logDebug("016022",
            "Created section map " + this.mapId + " for " + this.id);
        return this.data;
    }

    private float[] heights() {
        if (this.heights == null) {
            this.heights = new float[IMAGE_SIZE];
            Arrays.fill(this.heights, Float.NaN);
        }
        return this.heights;
    }

    public void releaseTransient() {
        this.heights = null;
        this.data = null;
    }

    public int getColoredCount() {
        MapItemSavedData saved = getData();
        if (saved == null) return 0;
        int count = 0;
        for (byte color : saved.colors) {
            if (color != 0) count++;
        }
        return count;
    }

    public boolean isColored(int px, int pz) {
        MapItemSavedData saved = getData();
        if (saved == null || px < 0 || px > 127 || pz < 0 || pz > 127) return false;
        return saved.colors[px + pz * 128] != 0;
    }

    public boolean setPixel(int px, int pz, MapColor color, double averageHeight, int fluidDepth) {
        if (px < 0 || px > 127 || pz < 0 || pz > 127) return false;
        if (color == null || color == MapColor.NONE) return false;

        MapItemSavedData saved = getData();
        if (saved == null) return false;

        heights()[px + pz * 128] = (float) averageHeight;

        boolean changed = writeColor(saved, px, pz, color.getPackedId(brightnessFor(px, pz, color, fluidDepth)));
        changed |= recomputeBrightness(px, pz + 1);
        return changed;
    }

    public boolean recomputeBrightness(int px, int pz) {
        if (px < 0 || px > 127 || pz < 0 || pz > 127) return false;
        if (this.heights == null) return false;

        MapItemSavedData saved = getData();
        if (saved == null) return false;

        int index = px + pz * 128;
        byte packed = saved.colors[index];
        if (packed == 0 || Float.isNaN(this.heights[index])) return false;

        MapColor color = MapColor.byId((packed & 0xFF) >> 2);
        if (color == MapColor.NONE || color == MapColor.WATER) return false;

        return writeColor(saved, px, pz, color.getPackedId(brightnessFor(px, pz, color, 0)));
    }

    private boolean writeColor(MapItemSavedData saved, int px, int pz, byte packed) {
        if (saved.colors[px + pz * 128] == packed) return false;
        saved.setColor(px, pz, packed);
        return true;
    }

    private MapColor.Brightness brightnessFor(int px, int pz, MapColor color, int fluidDepth) {
        if (color == MapColor.WATER) {
            double shade = fluidDepth * 0.1 + ((px + pz) & 1) * 0.2;
            if (shade < 0.5) return MapColor.Brightness.HIGH;
            if (shade > 0.9) return MapColor.Brightness.LOW;
            return MapColor.Brightness.NORMAL;
        }

        float[] h = heights();
        float here = h[px + pz * 128];
        float north = pz > 0 ? h[px + (pz - 1) * 128] : Float.NaN;
        if (Float.isNaN(here)) return MapColor.Brightness.NORMAL;
        if (Float.isNaN(north)) north = here;

        int blocksPerPixel = 1 << MAP_SCALE;
        double slope = (here - north) * 4.0 / (blocksPerPixel + 4)
            + (((px + pz) & 1) - 0.5) * 0.4;

        if (slope > 0.6) return MapColor.Brightness.HIGH;
        if (slope < -0.6) return MapColor.Brightness.LOW;
        return MapColor.Brightness.NORMAL;
    }

    @Override
    public void setId(String id) {
        if (id == null) return;
        this.id = id;
        this.pos = ChunkUtil.getChunkPos(id);
    }

    @Override
    public void setLevel(LevelAccessor levelAccessor) {
        if (levelAccessor == null || levelAccessor.isClientSide()) return;
        this.level = (ServerLevel) levelAccessor;
    }

    @Override
    public boolean isInit(String subClass) {
        return subClass.equals(ManagedChunkMap.class.getName()) && this.id != null;
    }

    @Override
    public ManagedChunkMap resolveSubData(LevelAccessor level, String id, @Nullable IMangedChunkData serialized) {
        if (id == null || level == null) return null;
        if (!(level instanceof ServerLevel serverLevel)) return (ManagedChunkMap) serialized;

        ChunkPos chunkPos = ChunkUtil.getChunkPos(id);
        if (!isSectionCenter(chunkPos)) return null;

        ManagedChunkMap subData = (ManagedChunkMap) serialized;
        if (subData == null || subData.id == null || DEFAULT_ID.equals(subData.id)) {
            subData = new ManagedChunkMap(serverLevel, id);
        } else {
            subData.setLevel(serverLevel);
            subData.setId(id);
        }

        MapChunkManager.register(serverLevel, subData);
        return subData;
    }

    @Override
    public void handleChunkLoaded(ChunkLoadingEvent.Load event) {
        if (this.id == null || DEFAULT_ID.equals(this.id)) return;
        setLevel(event.getLevel());
    }

    @Override
    public void handleChunkUnloaded(ChunkLoadingEvent.Unload event) {
        if (this.id == null || DEFAULT_ID.equals(this.id)) return;
        releaseTransient();
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        if (this.id == null || DEFAULT_ID.equals(this.id)) return tag;
        if (!hasMapId()) return tag;

        tag.putString("id", this.id);
        tag.putInt("mapId", this.mapId);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) return;

        if (tag.contains("id")) setId(tag.getString("id"));
        if (tag.contains("mapId")) this.mapId = tag.getInt("mapId");

        this.data = null;
        this.heights = null;
    }
}
