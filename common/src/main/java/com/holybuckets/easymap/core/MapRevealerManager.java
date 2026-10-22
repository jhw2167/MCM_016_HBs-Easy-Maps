package com.holybuckets.easymap.core;

import com.holybuckets.easymap.Constants;
import com.holybuckets.foundation.event.EventRegistrar;
import com.holybuckets.foundation.event.custom.PlayerInteractEvent;
import com.holybuckets.foundation.structure.StructureAPI;
import com.holybuckets.foundation.structure.StructureInfo;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static com.holybuckets.foundation.CommonClass.MESSAGER;

public class MapRevealerManager {

    public static final String TAG_MAP = "map";

    private MapRevealerManager() {
    }

    public static void init(EventRegistrar reg) {
        reg.registerOnPlayerInteract(PlayerInteractEvent.RightClickInteraction.class,
            MapRevealerManager::onRightClickInteraction);
    }

    private static void onRightClickInteraction(PlayerInteractEvent.RightClickInteraction event) {
        BlockPos pos = event.getPos();
        Level level = event.getLevel();
        Player player = event.getPlayer();

        if (player == null || pos == null || level == null || level.isClientSide()) return;
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!level.getBlockState(pos).is(Blocks.CARTOGRAPHY_TABLE)) return;
        if (!isMap(event.getItemStack())) return;

        onCartographyTableUse(serverLevel, serverPlayer, pos, event.getItemStack());
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

    public static void onCartographyTableUse(ServerLevel level, ServerPlayer player, BlockPos tablePos, ItemStack map) {
        MapItemSavedData existing = isBlankMap(map) ? null : MapItem.getSavedData(map, level);
        if (existing != null && existing.locked) {
            MESSAGER.sendBottomActionHint(player, Constants.MOD_ID, Constants.MOD_ID + "map_reveal.locked");
            return;
        }

        Integer mapId = MapChunkManager.getRegionMapId(level, tablePos.getX(), tablePos.getZ());
        if (mapId == null) {
            MESSAGER.sendBottomActionHint(player, Constants.MOD_ID, Constants.MOD_ID + "map_reveal.rejected");
            return;
        }
        MapItemSavedData mapData = MapItem.getSavedData(mapId, level);
        MapChunkManager.addScoutedChunk(player, mapData );

        ItemStack result = map;
        if (isBlankMap(map)) {
            result = new ItemStack(Items.FILLED_MAP);
            map.shrink(1);
        }

        result.getOrCreateTag().putInt(TAG_MAP, mapId);
        MapChunkManager.submitRegionChunks(level, MapItem.getSavedData(result, level));
        revealStructures(level, result);

        if (result!=map && !player.getInventory().add(result)) {
            player.drop(result, false);
        }

        MESSAGER.sendBottomActionHint(player, Constants.MOD_ID, Constants.MOD_ID + "map_reveal.created");
    }

    public static boolean revealStructures(ServerLevel level, ItemStack map) {
        MapItemSavedData data = MapItem.getSavedData(map, level);
        if (data == null) return false;

        Set<ResourceLocation> whitelist = ModConfig.getInstance().getRevealableStructures();
        if (whitelist.isEmpty()) return false;

        StructureAPI api = StructureAPI.get(level);
        if (api == null) return false;

        int halfSpan = MapChunkManager.REGION_SPAN / 2;
        BlockPos center = new BlockPos(data.centerX, 0, data.centerZ);
        int max = ModConfig.getMaxIconsPerMap();

        List<StructureInfo> candidates = api.nearestStructuresOfTypes(center, whitelist, max * 4);
        if (candidates == null || candidates.isEmpty()) return false;

        List<StructureInfo> inBounds = new ArrayList<>();
        for (StructureInfo info : candidates) {
            if (info == null || info.getOrigin() == null) continue;
            if (Math.abs(info.getOrigin().getX() - data.centerX) > halfSpan) continue;
            if (Math.abs(info.getOrigin().getZ() - data.centerZ) > halfSpan) continue;
            inBounds.add(info);
        }
        if (inBounds.isEmpty()) return false;

        inBounds.sort(Comparator.comparingDouble(i -> i.getOrigin().distSqr(center)));
        for (int i = 0; i < Math.min(max, inBounds.size()); i++) {
            StructureInfo info = inBounds.get(i);
            ResourceLocation loc = info.getStructureLocation();
            String id = (loc == null ? "unknown" : loc.toString()) + "@" + new ChunkPos(info.getOrigin());
            MapItemSavedData.addTargetDecoration(map, info.getOrigin(), id, MapDecoration.Type.RED_X);
        }
        return true;
    }
}
