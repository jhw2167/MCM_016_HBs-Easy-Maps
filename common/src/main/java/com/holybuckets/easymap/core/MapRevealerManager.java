package com.holybuckets.easymap.core;

import com.holybuckets.foundation.CommonClass;
import com.holybuckets.foundation.core.ChunkExplorerManager;
import com.holybuckets.foundation.event.EventRegistrar;
import com.holybuckets.foundation.event.custom.PlayerInteractEvent;
import com.holybuckets.foundation.event.custom.ServerTickEvent;
import com.holybuckets.foundation.event.custom.TickType;
import com.holybuckets.foundation.structure.StructureAPI;
import com.holybuckets.foundation.structure.StructureInfo;
import com.holybuckets.easymap.LoggerProject;
import com.holybuckets.easymap.config.ModConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.blay09.mods.balm.api.event.server.ServerStoppedEvent;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.holybuckets.foundation.CommonClass.MESSAGER;

/**
 * Engine class for processing map reveals, managing terrain and structure loads through Foundations
 */
public class MapRevealerManager {

    public static final String CLASS_ID = "016";

    private MapRevealerManager() {
    }

    public static final int MAX_CHUNK_SUBMISSIONS_PER_PASS = 64;

    private static final Map<ServerPlayer, ChunkPos> PENDING_REVEALS = new HashMap<>();
    private static final Map<ResourceKey<Level>, LongSet> SUBMITTED_CHUNKS = new HashMap<>();
    private static final Map<ResourceLocation, Byte> BIOME_COLOR_CACHE = new HashMap<>();

    public static void init(EventRegistrar reg) {
        reg.registerOnPlayerInteract(PlayerInteractEvent.RightClickInteraction.class,
            MapRevealerManager::onRightClickInteraction);
        reg.registerOnServerTick(TickType.ON_20_TICKS, MapRevealerManager::on20Ticks);
        reg.registerOnServerStopped(MapRevealerManager::onServerStopped);
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        PENDING_REVEALS.clear();
        SUBMITTED_CHUNKS.clear();
        BIOME_COLOR_CACHE.clear();
    }

    public static boolean markSubmitted(ServerLevel level, ChunkPos pos) {
        if (level == null || pos == null) return false;
        return SUBMITTED_CHUNKS
            .computeIfAbsent(level.dimension(), k -> new LongOpenHashSet())
            .add(pos.toLong());
    }

    public static boolean isSubmitted(ServerLevel level, ChunkPos pos) {
        if (level == null || pos == null) return false;
        LongSet submitted = SUBMITTED_CHUNKS.get(level.dimension());
        return submitted != null && submitted.contains(pos.toLong());
    }

    public static void clearSubmitted(ServerLevel level) {
        if (level == null) return;
        SUBMITTED_CHUNKS.remove(level.dimension());
    }

    private static void on20Ticks(ServerTickEvent event) {
        if (PENDING_REVEALS.isEmpty()) return;

        Iterator<Map.Entry<ServerPlayer, ChunkPos>> it = PENDING_REVEALS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ServerPlayer, ChunkPos> entry = it.next();
            ServerPlayer player = entry.getKey();
            ChunkPos corner = entry.getValue();

            if (player == null || player.hasDisconnected() || corner == null) {
                it.remove();
                continue;
            }
            if (!(player.level() instanceof ServerLevel level)) {
                it.remove();
                continue;
            }

            ItemStack map = findMapForCorner(player, level, corner);
            if (map.isEmpty()) {
                it.remove();
                continue;
            }

            MapItemSavedData data = MapItem.getSavedData(map, level);
            if (data == null) {
                it.remove();
                continue;
            }

            fillMapFace(level, data);
            fillFromBiomes(level, data);

            if (allChunksLoaded(level, corner, data)) {
                it.remove();
                MESSAGER.sendBottomActionHint(player, "map_reveal.complete");
                continue;
            }

            submitPendingChunks(level, corner, data);
        }
    }

    public static ChunkPos cornerChunk(MapItemSavedData data) {
        int halfSpan = 64 << data.scale;
        return new ChunkPos(new BlockPos(data.centerX - halfSpan, 0, data.centerZ - halfSpan));
    }

    public static boolean allChunksLoaded(ServerLevel level, ChunkPos corner, MapItemSavedData data) {
        int chunkSpan = 8 << data.scale;
        for (int x = 0; x < chunkSpan; x++) {
            for (int z = 0; z < chunkSpan; z++) {
                if (!level.hasChunk(corner.x + x, corner.z + z)) return false;
            }
        }
        return true;
    }

    public static int submitPendingChunks(ServerLevel level, ChunkPos corner, MapItemSavedData data) {
        int chunkSpan = 8 << data.scale;
        int submitted = 0;

        for (int x = 0; x < chunkSpan && submitted < MAX_CHUNK_SUBMISSIONS_PER_PASS; x++) {
            for (int z = 0; z < chunkSpan && submitted < MAX_CHUNK_SUBMISSIONS_PER_PASS; z++) {
                ChunkPos pos = new ChunkPos(corner.x + x, corner.z + z);
                if (level.hasChunk(pos.x, pos.z)) continue;
                if (!markSubmitted(level, pos)) continue;
                ChunkExplorerManager.submitPriorityChunk(level, pos);
                submitted++;
            }
        }
        return submitted;
    }

    public static void registerPendingReveal(ServerLevel level, ServerPlayer player, MapItemSavedData data) {
        if (level == null || player == null || data == null) return;

        ChunkPos corner = cornerChunk(data);
        if (allChunksLoaded(level, corner, data)) {
            PENDING_REVEALS.remove(player);
            return;
        }

        PENDING_REVEALS.put(player, corner);
        submitPendingChunks(level, corner, data);
        MESSAGER.sendBottomActionHint(player, "chunk_reveal_pending");
    }

    private static ItemStack findMapForCorner(ServerPlayer player, ServerLevel level, ChunkPos corner) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!isFilledMap(stack)) continue;
            MapItemSavedData data = MapItem.getSavedData(stack, level);
            if (data == null) continue;
            if (cornerChunk(data).equals(corner)) return stack;
        }
        return ItemStack.EMPTY;
    }

    private static void onRightClickInteraction(PlayerInteractEvent.RightClickInteraction event) {
        Player player = event.getPlayer();
        BlockPos pos = event.getPos();
        Level level = event.getLevel();

        if (player == null || pos == null || level == null) return;
        if (level.isClientSide()) return;
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!level.getBlockState(pos).is(Blocks.CARTOGRAPHY_TABLE)) return;

        ItemStack held = event.getItemStack();
        if (!isMap(held)) return;

        onCartographyTableUse(serverLevel, serverPlayer, pos, held);
    }

    public static final int MAX_SCALE_WITH_CEILING = 6;


    public static boolean isBlankMap(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.is(Items.MAP);
    }

    public static boolean isFilledMap(ItemStack stack) {
        return stack != null && !stack.isEmpty()
            && (stack.is(Items.FILLED_MAP) || stack.getItem() instanceof MapItem);
    }

    public static boolean isMap(ItemStack stack) {
        return isBlankMap(stack) || isFilledMap(stack);
    }


    public static void onCartographyTableUse(ServerLevel level, ServerPlayer player, BlockPos tablePos, ItemStack map)
    {
        int maxScale = ModConfig.getMaxEffectiveMapScale();
        int targetScale = Math.min(ModConfig.getRevealDefaultScale(), maxScale);

        if (level == null || tablePos == null || !isMap(map)) {
            onRevealRejected(level, player, map, -1);
            return;
        }

        MapItemSavedData data = isBlankMap(map) ? null : MapItem.getSavedData(map, level);

        if (data == null) {
            createRevealedMap(level, player, tablePos, map, targetScale);
            return;
        }

        if (data.locked) {
            onRevealRejected(level, player, map, data.scale);
            return;
        }

        if (!level.dimension().equals(data.dimension)) {
            recenterMap(level, player, tablePos, map, targetScale);
            return;
        }

        if (data.scale >= maxScale) {
            revealInPlace(level, player, tablePos, map, data.scale);
            return;
        }

        // Minecraft vanilla "snap to grid map position" logic
        int span = 128 << targetScale;
        int cellX = Mth.floor((tablePos.getX() + 64.0) / span);
        int cellZ = Mth.floor((tablePos.getZ() + 64.0) / span);
        int snapCenterX = cellX * span + span / 2 - 64;
        int snapCenterZ = cellZ * span + span / 2 - 64;

        if (data.scale == targetScale && data.centerX == snapCenterX && data.centerZ == snapCenterZ) {
            revealInPlace(level, player, tablePos, map, data.scale);
            return;
        }

        boolean sameSection = Mth.floor((data.centerX + 64.0) / span) == cellX
            && Mth.floor((data.centerZ + 64.0) / span) == cellZ;

        if (data.scale < targetScale && sameSection) {
            expandToScale(level, player, tablePos, map, targetScale);
            return;
        }

        recenterMap(level, player, tablePos, map, targetScale);
    }

    public static final int MAX_SAMPLES_PER_AXIS = 4;
    public static final String TAG_MAP = "map";

    public static ItemStack createRevealedMap(ServerLevel level, ServerPlayer player, BlockPos target, ItemStack map, int targetScale) {
        ItemStack fresh = MapItem.create(level, target.getX(), target.getZ(), (byte) targetScale, true, true);
        Integer newId = MapItem.getMapId(fresh);
        if (newId == null) return ItemStack.EMPTY;

        ItemStack result;
        if (isBlankMap(map)) {
            result = fresh;
            map.shrink(1);
        } else {
            map.getOrCreateTag().putInt(TAG_MAP, newId);
            result = map;
        }

        MapItemSavedData data = MapItem.getSavedData(result, level);
        if (data == null) return ItemStack.EMPTY;

        int painted = fillMapFace(level, data);
        painted += fillFromBiomes(level, data);
        revealStructures(level, player, result);
        registerPendingReveal(level, player, data);

        if (result != map && !player.getInventory().add(result)) {
            player.drop(result, false);
        }

        MESSAGER.sendBottomActionHint(player, painted > 0 ? "map_reveal.created" : "map_reveal.nothing");
        return result;
    }

    public static boolean revealInPlace(ServerLevel level, ServerPlayer player, BlockPos target, ItemStack map, int currentScale) {
        MapItemSavedData data = MapItem.getSavedData(map, level);
        if (data == null) return false;

        int painted = fillMapFace(level, data);
        painted += fillFromBiomes(level, data);
        revealStructures(level, player, map);
        registerPendingReveal(level, player, data);

        MESSAGER.sendBottomActionHint(player, painted > 0 ? "map_reveal.filled" : "map_reveal.nothing");
        return painted > 0;
    }

    public static boolean expandToScale(ServerLevel level, ServerPlayer player, BlockPos target, ItemStack map, int targetScale) {
        MapItemSavedData old = MapItem.getSavedData(map, level);
        if (old == null) return false;

        int oldScale = old.scale;
        int oldCenterX = old.centerX;
        int oldCenterZ = old.centerZ;
        byte[] oldColors = old.colors.clone();

        ItemStack fresh = MapItem.create(level, target.getX(), target.getZ(), (byte) targetScale, true, true);
        Integer newId = MapItem.getMapId(fresh);
        if (newId == null) return false;

        map.getOrCreateTag().putInt(TAG_MAP, newId);
        MapItemSavedData data = MapItem.getSavedData(map, level);
        if (data == null) return false;

        copyColors(oldColors, oldScale, oldCenterX, oldCenterZ, data);
        fillMapFace(level, data);
        fillFromBiomes(level, data);
        revealStructures(level, player, map);
        registerPendingReveal(level, player, data);

        MESSAGER.sendBottomActionHint(player, "map_reveal.expanded");
        return true;
    }

    public static boolean recenterMap(ServerLevel level, ServerPlayer player, BlockPos target, ItemStack map, int targetScale) {
        ItemStack fresh = MapItem.create(level, target.getX(), target.getZ(), (byte) targetScale, true, true);
        Integer newId = MapItem.getMapId(fresh);
        if (newId == null) return false;

        map.getOrCreateTag().putInt(TAG_MAP, newId);
        MapItemSavedData data = MapItem.getSavedData(map, level);
        if (data == null) return false;

        fillMapFace(level, data);
        fillFromBiomes(level, data);
        revealStructures(level, player, map);
        registerPendingReveal(level, player, data);

        MESSAGER.sendBottomActionHint(player, "map_reveal.recentered");
        return true;
    }

    public static void onRevealRejected(ServerLevel level, ServerPlayer player, ItemStack map, int currentScale) {
        MESSAGER.sendBottomActionHint(player, "map_reveal.rejected");
    }

    public static boolean revealStructures(ServerLevel level, ServerPlayer player, ItemStack map) {
        MapItemSavedData data = MapItem.getSavedData(map, level);
        if (data == null) return false;

        List<StructureInfo> found = findRevealableStructures(level, data);
        if (found.isEmpty()) return false;

        for (StructureInfo info : found) {
            MapItemSavedData.addTargetDecoration(map, info.getOrigin(), decorationId(info), decorationType(info));
        }
        return true;
    }

    public static int fillMapFace(ServerLevel level, MapItemSavedData data) {
        if (level == null || data == null) return 0;
        if (!level.dimension().equals(data.dimension)) return 0;

        int blocksPerPixel = 1 << data.scale;
        int stride = Math.max(1, blocksPerPixel / MAX_SAMPLES_PER_AXIS);
        int originX = Math.floorDiv(data.centerX, blocksPerPixel) - 64;
        int originZ = Math.floorDiv(data.centerZ, blocksPerPixel) - 64;
        int minY = level.getMinBuildHeight();
        boolean hasCeiling = level.dimensionType().hasCeiling();

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        LevelChunk cached = null;
        long cachedKey = Long.MIN_VALUE;
        int painted = 0;

        for (int px = 0; px < 128; px++) {
            double previousHeight = 0.0;
            for (int pz = 0; pz < 128; pz++) {
                int blockX = (originX + px) * blocksPerPixel;
                int blockZ = (originZ + pz) * blocksPerPixel;

                Multiset<MapColor> counts = LinkedHashMultiset.create();
                double heightSum = 0.0;
                int fluidDepth = 0;
                int samples = 0;

                for (int sx = 0; sx < blocksPerPixel; sx += stride) {
                    for (int sz = 0; sz < blocksPerPixel; sz += stride) {
                        int worldX = blockX + sx;
                        int worldZ = blockZ + sz;

                        long key = ChunkPos.asLong(SectionPos.blockToSectionCoord(worldX), SectionPos.blockToSectionCoord(worldZ));
                        if (key != cachedKey) {
                            cached = level.getChunkSource().getChunkNow(
                                SectionPos.blockToSectionCoord(worldX), SectionPos.blockToSectionCoord(worldZ));
                            cachedKey = key;
                        }
                        if (cached == null || cached.isEmpty()) continue;

                        if (hasCeiling) {
                            int noise = worldX + worldZ * 231871;
                            noise = noise * noise * 31287121 + noise * 11;
                            if ((noise >> 20 & 1) == 0) {
                                counts.add(Blocks.DIRT.defaultBlockState().getMapColor(level, BlockPos.ZERO), 10);
                            } else {
                                counts.add(Blocks.STONE.defaultBlockState().getMapColor(level, BlockPos.ZERO), 100);
                            }
                            heightSum += 100.0;
                            samples++;
                            continue;
                        }

                        cursor.set(worldX, 0, worldZ);
                        int y = cached.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ) + 1;
                        BlockState state;

                        if (y <= minY + 1) {
                            state = Blocks.BEDROCK.defaultBlockState();
                        } else {
                            do {
                                y--;
                                cursor.setY(y);
                                state = cached.getBlockState(cursor);
                            } while (state.getMapColor(level, cursor) == MapColor.NONE && y > minY);

                            if (y > minY && !state.getFluidState().isEmpty()) {
                                int depthY = y - 1;
                                probe.set(cursor);
                                BlockState below;
                                do {
                                    probe.setY(depthY--);
                                    below = cached.getBlockState(probe);
                                    fluidDepth++;
                                } while (depthY > minY && !below.getFluidState().isEmpty());
                                state = correctStateForFluid(level, state, cursor);
                            }
                        }

                        data.checkBanners(level, worldX, worldZ);
                        heightSum += y;
                        counts.add(state.getMapColor(level, cursor));
                        samples++;
                    }
                }

                if (samples == 0) continue;

                double averageHeight = heightSum / samples;
                fluidDepth = fluidDepth / samples;
                MapColor color = Iterables.getFirst(Multisets.copyHighestCountFirst(counts), MapColor.NONE);
                MapColor.Brightness brightness;

                if (color == MapColor.WATER) {
                    double shade = fluidDepth * 0.1 + ((px + pz) & 1) * 0.2;
                    if (shade < 0.5) brightness = MapColor.Brightness.HIGH;
                    else if (shade > 0.9) brightness = MapColor.Brightness.LOW;
                    else brightness = MapColor.Brightness.NORMAL;
                } else {
                    double slope = (averageHeight - previousHeight) * 4.0 / (blocksPerPixel + 4)
                        + (((px + pz) & 1) - 0.5) * 0.4;
                    if (slope > 0.6) brightness = MapColor.Brightness.HIGH;
                    else if (slope < -0.6) brightness = MapColor.Brightness.LOW;
                    else brightness = MapColor.Brightness.NORMAL;
                }

                previousHeight = averageHeight;
                if (color == MapColor.NONE) continue;

                byte packed = color.getPackedId(brightness);
                if (data.colors[px + pz * 128] != packed) {
                    data.setColor(px, pz, packed);
                    painted++;
                }
            }
        }

        if (ModConfig.isDevMode()) {
            LoggerProject.logDebug("016012",
                "Filled " + painted + " pixels at scale " + data.scale
                + " center " + data.centerX + ", " + data.centerZ);
        }
        return painted;
    }

    public static int fillFromBiomes(ServerLevel level, MapItemSavedData data) {
        if (level == null || data == null) return 0;
        if (!level.dimension().equals(data.dimension)) return 0;

        int blocksPerPixel = 1 << data.scale;
        int originX = Math.floorDiv(data.centerX, blocksPerPixel) - 64;
        int originZ = Math.floorDiv(data.centerZ, blocksPerPixel) - 64;
        int painted = 0;

        for (int px = 0; px < 128; px++) {
            for (int pz = 0; pz < 128; pz++) {
                if (data.colors[px + pz * 128] != 0) continue;

                int worldX = (originX + px) * blocksPerPixel;
                int worldZ = (originZ + pz) * blocksPerPixel;
                byte packed = biomeColor(level, worldX, worldZ);
                if (packed == 0) continue;

                data.setColor(px, pz, packed);
                painted++;
            }
        }

        if (ModConfig.isDevMode()) {
            LoggerProject.logDebug("016013",
                "Biome layer painted " + painted + " pixels at scale " + data.scale);
        }
        return painted;
    }

    private static byte biomeColor(ServerLevel level, int blockX, int blockZ) {
        Holder<Biome> holder = level.getUncachedNoiseBiome(blockX >> 2, 16, blockZ >> 2);
        if (holder == null) return 0;

        ResourceLocation key = holder.unwrapKey().map(ResourceKey::location).orElse(null);
        if (key == null) return 0;

        Byte cached = BIOME_COLOR_CACHE.get(key);
        if (cached != null) return cached;

        Biome biome = holder.value();
        int rgb = holder.is(BiomeTags.WATER_ON_MAP_OUTLINES)
            ? biome.getWaterColor()
            : biome.getGrassColor(0.0, 0.0);

        MapColor nearest = nearestMapColor(rgb);
        byte packed = nearest == MapColor.NONE ? 0 : nearest.getPackedId(MapColor.Brightness.NORMAL);
        BIOME_COLOR_CACHE.put(key, packed);
        return packed;
    }

    private static MapColor nearestMapColor(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        MapColor best = MapColor.NONE;
        int bestDistance = Integer.MAX_VALUE;

        for (int id = 1; id < 64; id++) {
            MapColor candidate = MapColor.byId(id);
            if (candidate == MapColor.NONE) continue;

            int col = candidate.col;
            int dr = r - ((col >> 16) & 0xFF);
            int dg = g - ((col >> 8) & 0xFF);
            int db = b - (col & 0xFF);
            int distance = dr * dr + dg * dg + db * db;

            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    public static int copyColors(byte[] oldColors, int oldScale, int oldCenterX, int oldCenterZ, MapItemSavedData data) {
        if (oldColors == null || data == null) return 0;

        int oldBlocksPerPixel = 1 << oldScale;
        int newBlocksPerPixel = 1 << data.scale;
        int oldOriginX = Math.floorDiv(oldCenterX, oldBlocksPerPixel) - 64;
        int oldOriginZ = Math.floorDiv(oldCenterZ, oldBlocksPerPixel) - 64;
        int newOriginX = Math.floorDiv(data.centerX, newBlocksPerPixel) - 64;
        int newOriginZ = Math.floorDiv(data.centerZ, newBlocksPerPixel) - 64;
        int copied = 0;

        for (int px = 0; px < 128; px++) {
            for (int pz = 0; pz < 128; pz++) {
                int worldX = (newOriginX + px) * newBlocksPerPixel;
                int worldZ = (newOriginZ + pz) * newBlocksPerPixel;
                int oldPx = Math.floorDiv(worldX, oldBlocksPerPixel) - oldOriginX;
                int oldPz = Math.floorDiv(worldZ, oldBlocksPerPixel) - oldOriginZ;

                if (oldPx < 0 || oldPx > 127 || oldPz < 0 || oldPz > 127) continue;
                byte packed = oldColors[oldPx + oldPz * 128];
                if (packed == 0) continue;

                data.setColor(px, pz, packed);
                copied++;
            }
        }
        return copied;
    }

    private static BlockState correctStateForFluid(Level level, BlockState state, BlockPos pos) {
        FluidState fluid = state.getFluidState();
        return !fluid.isEmpty() && !state.isFaceSturdy(level, pos, net.minecraft.core.Direction.UP)
            ? fluid.createLegacyBlock() : state;
    }

    public static int getEffectiveScale(MapItemSavedData data) {
        if (data == null) return 0;
        return ModConfig.getEffectiveMapScale(data.scale);
    }

    public static int getMapHalfSpan(MapItemSavedData data) {
        return 64 << getEffectiveScale(data);
    }

    @Nullable
    public static MapItemSavedData getMapData(ServerLevel level, ItemStack map) {
        if (!isMap(map)) return null;
        return MapItem.getSavedData(map, level);
    }

    /**
     *  On Map reveal, place icons over familiar structures in the vicinity
     *  This method uses Foundations structures to find nearby structure, mathces them against config to determin
     *  if they can be on the map and places them on the map - err sends the info to the client
     *
     */
    public static List<StructureInfo> findRevealableStructures(ServerLevel level, MapItemSavedData data) {
        if (data == null) return List.of();

        ModConfig config = ModConfig.getInstance();
        Set<ResourceLocation> whitelist = config.getRevealableStructures();
        if (whitelist.isEmpty()) return List.of();

        StructureAPI api = StructureAPI.get(level);
        if (api == null) return List.of();

        int halfSpan = getMapHalfSpan(data);
        BlockPos center = new BlockPos(data.centerX, 0, data.centerZ);

        List<StructureInfo> candidates =
            api.nearestStructuresOfTypes(center, whitelist, ModConfig.getMaxIconsPerMap() * 4);
        if (candidates == null || candidates.isEmpty()) return List.of();

        List<StructureInfo> inBounds = new ArrayList<>();
        for (StructureInfo info : candidates) {
            if (info == null || info.getOrigin() == null) continue;
            if (!isWithinMap(info.getOrigin(), data.centerX, data.centerZ, halfSpan)) continue;
            inBounds.add(info);
        }

        inBounds.sort(Comparator.comparingDouble(i -> i.getOrigin().distSqr(center)));

        int max = ModConfig.getMaxIconsPerMap();
        return inBounds.size() <= max ? inBounds : inBounds.subList(0, max);
    }

    public static boolean isWithinMap(BlockPos pos, int centerX, int centerZ, int halfSpan) {
        if (pos == null) return false;
        return Math.abs(pos.getX() - centerX) <= halfSpan
            && Math.abs(pos.getZ() - centerZ) <= halfSpan;
    }

    public static String decorationId(StructureInfo info) {
        ResourceLocation loc = info.getStructureLocation();
        String name = loc == null ? "unknown" : loc.toString();
        return name + "@" + new ChunkPos(info.getOrigin());
    }

    public static MapDecoration.Type decorationType(StructureInfo info) {
        return MapDecoration.Type.RED_X;
    }

    public static void primeMapChunks(ServerLevel level, ItemStack map) {
        MapItemSavedData data = getMapData(level, map);
        if (data == null) return;
        primeMapChunks(level, data);
    }

    public static void primeMapChunks(ServerLevel level, MapItemSavedData data) {
        if (level == null || data == null) return;
        if (!ModConfig.primeEdgeChunks()) return;

        int halfSpan = getMapHalfSpan(data);
        int stride = ModConfig.getPrimeChunkStride();

        ChunkPos min = new ChunkPos(new BlockPos(data.centerX - halfSpan, 0, data.centerZ - halfSpan));
        ChunkPos max = new ChunkPos(new BlockPos(data.centerX + halfSpan, 0, data.centerZ + halfSpan));

        Set<ChunkPos> positions = new LinkedHashSet<>();
        for (int x = min.x; x <= max.x; x += stride) {
            for (int z = min.z; z <= max.z; z += stride) {
                positions.add(new ChunkPos(x, z));
            }
        }

        ChunkExplorerManager.submitPriorityChunks(level, positions);

        if (ModConfig.isDevMode()) {
            LoggerProject.logDebug("016010",
                "Primed " + positions.size() + " chunks for map centered at "
                + data.centerX + ", " + data.centerZ + " halfSpan " + halfSpan);
        }
    }
}
