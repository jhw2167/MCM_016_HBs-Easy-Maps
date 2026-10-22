package com.holybuckets.easymap.client;

import com.holybuckets.easymap.config.ModConfig;
import com.holybuckets.easymap.config.OverlayAlignment;
import com.holybuckets.easymap.core.PlayerHomeSync;
import com.holybuckets.foundation.HBUtil;
import com.holybuckets.foundation.client.ClientEventRegistrar;
import com.holybuckets.foundation.event.EventRegistrar;
import com.holybuckets.foundation.event.custom.ClientTickEvent;
import com.holybuckets.foundation.event.custom.PlayerInteractEvent;
import com.holybuckets.foundation.player.ManagedPlayer;
import com.holybuckets.foundation.sample.SamplePlayerData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.blay09.mods.balm.api.event.client.GuiDrawEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Client HUD overlay for pinned maps and compasses, with all state kept in RAM.
 */
public class MapOverlayClient {

    private static final ResourceLocation MAP_BACKGROUND = new ResourceLocation("textures/map/map_background.png");
    private static final ResourceLocation MAP_ICONS = new ResourceLocation("textures/map/map_icons.png");
    private static final int MARGIN = 2;
    private static final int LINE_GAP = 1;
    private static final int TEXT_COLOR = 0xFFFFFF;

    private static Integer pinnedMapId = null;
    private static boolean compassPinned = false;

    private MapOverlayClient() {
    }

    public static void init(ClientEventRegistrar reg) {
        reg.registerOnGuiDrawPost(MapOverlayClient::onGuiDrawPost);
        EventRegistrar serverReg = EventRegistrar.getInstance();
        serverReg.registerOnPlayerInteract(PlayerInteractEvent.RightClickInteraction.class, MapOverlayClient::onUseItem);
    }


    //** INPUT **//

    public static void onUseItem(PlayerInteractEvent.RightClickInteraction event) {
        ItemStack item = event.getItemStack();
        if(item==null || item.isEmpty()) return;
        if(event.isCanceled()) return;
        if(event.getPos()!=null) {
            BlockEntity entity = event.getLevel().getBlockEntity(event.getPos());
            if(entity!=null) return;
        }

        if(item.is(Items.COMPASS))
            compassPinned = !compassPinned;
        if(item.is(Items.FILLED_MAP))
            pinnedMapId = (pinnedMapId==null) ? MapItem.getMapId(item) : null;
    }


    private static boolean hasCompass(LocalPlayer player) {
        if (player == null) return false;
        return player.getMainHandItem().is(Items.COMPASS) || player.getOffhandItem().is(Items.COMPASS);
    }

    //** RENDER **//
    private static void onGuiDrawPost(GuiDrawEvent.Post event) {
        if (event.getElement() != GuiDrawEvent.Element.ALL) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.options.hideGui || mc.options.renderDebug) return;

        MapItemSavedData data = (pinnedMapId == null) ? null : MapItem.getSavedData(pinnedMapId, mc.level);
        boolean compassActive = compassPinned || hasCompass(player);
        if (data == null && !compassActive) return;

        GuiGraphics g = event.getGuiGraphics();
        boolean right = ModConfig.getOverlayAlignment() == OverlayAlignment.RIGHT;
        int edgeX = right ? mc.getWindow().getGuiScaledWidth() - MARGIN : MARGIN;
        int y = MARGIN;

        if (data != null) {
            int size = ModConfig.getOverlayMapSize();
            int x = right ? edgeX - size : edgeX;
            renderMap(g, mc, player, pinnedMapId, data, x, y, size);
            y += size + MARGIN;
        }

        drawLines(g, mc.font, buildLines(mc, player, compassActive), edgeX, y, right, ModConfig.getOverlayTextScale());
    }

    private static void renderMap(GuiGraphics g, Minecraft mc, LocalPlayer player, int mapId,
                                  MapItemSavedData data, int x, int y, int size) {
        g.blit(MAP_BACKGROUND, x, y, 0.0F, 0.0F, size, size, size, size);

        int inset = Math.max(1, Math.round(size * 7.0F / 142.0F));
        float scale = (size - inset * 2) / 128.0F;

        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(x + inset, y + inset, 1.0F);
        pose.scale(scale, scale, -1.0F);
        mc.gameRenderer.getMapRenderer().render(pose, g.bufferSource(), mapId, data, false, LightTexture.FULL_BRIGHT);
        g.flush();
        pose.popPose();

        if (!isCarried(player, mapId)) {
        //    renderPlayerMarker(g, player, data, x + inset, y + inset, scale);
        }
    }

    private static boolean isCarried(LocalPlayer player, int mapId) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.getItem() instanceof MapItem && Integer.valueOf(mapId).equals(MapItem.getMapId(stack))) return true;
        }
        return false;
    }

    private static void renderPlayerMarker(GuiGraphics g, LocalPlayer player, MapItemSavedData data,
                                           int mapX, int mapY, float scale) {
        if (player.level().dimension() != data.dimension) return;

        int blocksPerPixel = 1 << data.scale;
        float px = Mth.clamp((float) (player.getX() - data.centerX) / blocksPerPixel + 64.0F, 0.0F, 128.0F);
        float pz = Mth.clamp((float) (player.getZ() - data.centerZ) / blocksPerPixel + 64.0F, 0.0F, 128.0F);

        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(mapX + px * scale, mapY + pz * scale, 2.0F);
        pose.scale(scale, scale, 1.0F);
        pose.mulPose(Axis.ZP.rotationDegrees(player.getYRot() + 180.0F));
        g.blit(MAP_ICONS, -4, -4, 0.0F, 0.0F, 8, 8, 128, 128);
        pose.popPose();
    }

    private static List<String> buildLines(Minecraft mc, LocalPlayer player, boolean compassActive) {
        List<String> lines = new ArrayList<>();
        BlockPos pos = player.blockPosition();
        lines.add(biomeName(mc.level.getBiome(pos)));
        lines.add(formatPos(pos));
        if (!compassActive) return lines;

        lines.add("Home: " + formatHome(mc, player));
        Direction dir = player.getDirection();
        String alignment = dir.getName() + ",";
        switch (dir) {
            case NORTH -> alignment += "-Z";
            case SOUTH -> alignment += "+Z";
            case WEST -> alignment += "-X";
            case EAST -> alignment += "+X";
        }
        lines.add("Facing: " + alignment);
        player.getLastDeathLocation().ifPresent(death ->
            lines.add("Last Death: " + formatPos(death.pos()) + dimensionSuffix(player, death.dimension())));
        return lines;
    }

    private static String biomeName(Holder<Biome> biome) {
        return HBUtil.LevelUtil.getBiomeSimpleName(biome);
    }

    private static String formatHome(Minecraft mc, LocalPlayer player) {
        SamplePlayerData spd = (SamplePlayerData) ManagedPlayer.getManagedPlayer(player).getSubclass(SamplePlayerData.class);
        BlockPos spawn = spd.getHomePos();
        if (spawn == null) return formatPos(mc.level.getSharedSpawnPos());
        return formatPos(spawn);
    }

    private static String dimensionSuffix(LocalPlayer player, ResourceKey<Level> dimension) {
        if (dimension == null || dimension == player.level().dimension()) return "";
        return " (" + dimension.location().getPath() + ")";
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private static void drawLines(GuiGraphics g, Font font, List<String> lines, int edgeX, int y,
                                  boolean right, float scale) {
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(edgeX, y, 0.0F);
        pose.scale(scale, scale, 1.0F);
        int lineY = 0;
        for (String line : lines) {
            int x = right ? -font.width(line) : 0;
            g.drawString(font, line, x, lineY, TEXT_COLOR, true);
            lineY += font.lineHeight + LINE_GAP;
        }
        pose.popPose();
    }
}
