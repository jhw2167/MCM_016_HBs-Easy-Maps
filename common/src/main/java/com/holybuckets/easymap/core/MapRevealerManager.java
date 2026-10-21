package com.holybuckets.easymap.core;

import com.holybuckets.foundation.core.ChunkExplorerManager;
import com.holybuckets.foundation.event.EventRegistrar;
import com.holybuckets.foundation.event.custom.PlayerInteractEvent;
import com.holybuckets.foundation.event.custom.ServerTickEvent;
import com.holybuckets.foundation.event.custom.TickType;
import com.holybuckets.foundation.model.ManagedChunkUtility;
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
import net.minecraft.world.level.block.Blocks;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.blay09.mods.balm.api.event.server.ServerStoppedEvent;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
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

    private static final Map<ServerPlayer, ItemStack> PENDING_REVEALS = new HashMap<>();

    public static void init(EventRegistrar reg) {
        reg.registerOnPlayerInteract(PlayerInteractEvent.RightClickInteraction.class,
            MapRevealerManager::onRightClickInteraction);
        reg.registerOnServerTick(TickType.ON_20_TICKS, MapRevealerManager::on20Ticks);
        reg.registerOnServerStopped(MapRevealerManager::onServerStopped);
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        PENDING_REVEALS.clear();
    }


    public static int halfSpanBlocks(MapItemSavedData data) {
        return 64 << data.scale;
    }

    public static int halfSpanChunks(MapItemSavedData data) {
        return 4 << data.scale;
    }

    public static ChunkPos centerChunk(MapItemSavedData data) {
        return new ChunkPos(data.centerX >> 4, data.centerZ >> 4);
    }
    private static void on20Ticks(ServerTickEvent event) {
        if (PENDING_REVEALS.isEmpty()) return;

        Iterator<Map.Entry<ServerPlayer, ItemStack>> it = PENDING_REVEALS.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<ServerPlayer, ItemStack> entry = it.next();
            ServerPlayer player = entry.getKey();
            ItemStack map = entry.getValue();

            if (player == null || player.hasDisconnected() || map == null || map.isEmpty()) {
                it.remove();
                continue;
            }
            if (!(player.level() instanceof ServerLevel level)) {
                it.remove(); continue;
            }

            MapItemSavedData data = MapItem.getSavedData(map, level);
            if (data == null) {
                it.remove(); continue;
            }

            fillAroundPlayer(level, player, map, data);

            if (testAllChunksLoaded(level, data)) {
                it.remove();
                MESSAGER.sendBottomActionHint(player, "map_reveal.complete");
                continue;
            }

            submitPendingChunks(level, data);
        }
    }

    public static boolean testAllChunksLoaded(ServerLevel level, MapItemSavedData data) {
        ChunkPos center = centerChunk(data);
        int radius = halfSpanChunks(data);
        ChunkPos start = new ChunkPos(center.x - radius, center.z - radius);
        ChunkPos end = new ChunkPos(center.x + radius - 1, center.z + radius - 1);
        ManagedChunkUtility util = ManagedChunkUtility.getInstance(level);
        for(int x = start.x; x <= end.x; x++) {
            for(int z = start.z; z <= end.z; z++) {
                if(!util.isChunkInitialized(x, z)) return false;
            }
        }
        return true;
    }


    public static void submitPendingChunks(ServerLevel level, MapItemSavedData data) {
        ChunkPos center = centerChunk(data);
        int radius = halfSpanChunks(data);
        ChunkPos start = new ChunkPos(center.x - radius, center.z - radius);
        ChunkPos end = new ChunkPos(center.x + radius - 1, center.z + radius - 1);

        for(int x = start.x; x <= end.x; x++) {
            for(int z = start.z; z <= end.z; z++) {
                ChunkPos pos = new ChunkPos(x, z);
                if (level.hasChunk(pos.x, pos.z)) continue;
                ChunkExplorerManager.submitPriorityChunk(level, pos);
            }
        }
    }

    public static void registerPendingReveal(ServerLevel level, ServerPlayer player, ItemStack map) {
        if (level == null || player == null || map == null) return;

        MapItem.renderBiomePreviewMap(level, map);
        MapItemSavedData data = MapItem.getSavedData(map, level);
        if (data == null) return;

        if (testAllChunksLoaded(level, data)) {
            PENDING_REVEALS.remove(player);
            MESSAGER.sendBottomActionHint(player, "map_reveal.complete");
            return;
        }

        submitPendingChunks(level, data);
        PENDING_REVEALS.put(player, map);
        MESSAGER.sendBottomActionHint(player, "chunk_reveal_pending");
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

    public static boolean isWithinMap(BlockPos pos, MapItemSavedData data) {
        if (pos == null || data == null) return false;
        int halfSpan = halfSpanBlocks(data);
        return Math.abs(pos.getX() - data.centerX) <= halfSpan
            && Math.abs(pos.getZ() - data.centerZ) <= halfSpan;
    }

    public static void onCartographyTableUse(ServerLevel level, ServerPlayer player, BlockPos tablePos, ItemStack map)
    {
        if (level == null || player == null || tablePos == null || !isMap(map)) {
            onRevealRejected(player, "map_reveal.rejected");
            return;
        }

        MapItemSavedData data = isBlankMap(map) ? null : MapItem.getSavedData(map, level);

        if (data == null) {
            createRevealedMap(level, player, tablePos, map);
            return;
        }

        if (data.locked) {
            onRevealRejected(player, "map_reveal.locked");
            return;
        }

        if (!level.dimension().equals(data.dimension)) {
            onRevealRejected(player, "map_reveal.wrong_dimension");
            return;
        }

        if (!isWithinMap(tablePos, data)) {
            onRevealRejected(player, "map_reveal.out_of_range");
            return;
        }

        revealInPlace(level, player, map);
    }

    public static final String TAG_MAP = "map";

    public static ItemStack createRevealedMap(ServerLevel level, ServerPlayer player, BlockPos target, ItemStack map) {
        int scale = Math.min(ModConfig.getRevealDefaultScale(), MapItemSavedData.MAX_SCALE);

        ItemStack fresh = MapItem.create(level, target.getX(), target.getZ(), (byte) scale, true, true);
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

        fillAroundPlayer(level, player, result, data);
        revealStructures(level, player, result);

        if (result != map && !player.getInventory().add(result)) {
            player.drop(result, false);
        }

        registerPendingReveal(level, player, result);
        return result;
    }

    public static boolean revealInPlace(ServerLevel level, ServerPlayer player, ItemStack map) {
        MapItemSavedData data = MapItem.getSavedData(map, level);
        if (data == null) return false;

        fillAroundPlayer(level, player, map, data);
        revealStructures(level, player, map);
        registerPendingReveal(level, player, map);
        return true;
    }

    public static void onRevealRejected(ServerPlayer player, String messageKey) {
        if (player == null) return;
        MESSAGER.sendBottomActionHint(player, messageKey);
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

    public static final int COLUMN_PHASES = 16;

    public static void fillAroundPlayer(ServerLevel level, ServerPlayer player, ItemStack map, MapItemSavedData data) {
        if (level == null || player == null || data == null) return;
        if (!(map.getItem() instanceof MapItem mapItem)) return;
        if (!level.dimension().equals(data.dimension)) return;
        if (data.locked) return;

        for (int phase = 0; phase < COLUMN_PHASES; phase++) {
            mapItem.update(level, player, data);
        }
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

        BlockPos center = new BlockPos(data.centerX, 0, data.centerZ);

        List<StructureInfo> candidates =
            api.nearestStructuresOfTypes(center, whitelist, ModConfig.getMaxIconsPerMap() * 4);
        if (candidates == null || candidates.isEmpty()) return List.of();

        List<StructureInfo> inBounds = new ArrayList<>();
        for (StructureInfo info : candidates) {
            if (info == null || info.getOrigin() == null) continue;
            if (!isWithinMap(info.getOrigin(), data)) continue;
            inBounds.add(info);
        }

        inBounds.sort(Comparator.comparingDouble(i -> i.getOrigin().distSqr(center)));

        int max = ModConfig.getMaxIconsPerMap();
        return inBounds.size() <= max ? inBounds : inBounds.subList(0, max);
    }

    public static String decorationId(StructureInfo info) {
        ResourceLocation loc = info.getStructureLocation();
        String name = loc == null ? "unknown" : loc.toString();
        return name + "@" + new ChunkPos(info.getOrigin());
    }

    public static MapDecoration.Type decorationType(StructureInfo info) {
        return MapDecoration.Type.RED_X;
    }


}
