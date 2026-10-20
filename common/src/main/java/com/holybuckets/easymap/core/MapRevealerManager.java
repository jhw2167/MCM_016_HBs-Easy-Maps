package com.holybuckets.easymap.core;

import com.holybuckets.foundation.core.ChunkExplorerManager;
import com.holybuckets.foundation.event.EventRegistrar;
import com.holybuckets.foundation.event.custom.PlayerInteractEvent;
import com.holybuckets.foundation.structure.StructureAPI;
import com.holybuckets.foundation.structure.StructureInfo;
import com.holybuckets.easymap.LoggerProject;
import com.holybuckets.easymap.config.ModConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Engine class for processing map reveals, managing terrain and structure loads through Foundations
 */
public class MapRevealerManager {

    public static final String CLASS_ID = "016";

    private MapRevealerManager() {
    }

    public static void init(EventRegistrar reg) {
        reg.registerOnPlayerInteract(PlayerInteractEvent.RightClickInteraction.class,
            MapRevealerManager::onRightClickInteraction);
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

    public static boolean isMap(ItemStack stack) {
        return stack != null && !stack.isEmpty()
            && (stack.is(Items.FILLED_MAP) || stack.getItem() instanceof MapItem);
    }

    /**
     * Will reveal a large area centered on the table with the map
     */
    public static void onCartographyTableUse(ServerLevel level, ServerPlayer player, BlockPos tablePos, ItemStack map) {
        MapItem
    }

    public static boolean revealStructures(ServerLevel level, ServerPlayer player, ItemStack map) {
        return false;
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
