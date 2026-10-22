package com.holybuckets.easymap.mixin;

import net.minecraft.network.protocol.Packet;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import javax.annotation.Nullable;

@Mixin(MapItemSavedData.HoldingPlayer.class)
public interface HoldingPlayerAccessor {

    @Invoker("markColorsDirty")
    void easymapMarkColorsDirty(int x, int y);

    @Invoker("markDecorationsDirty")
    void easymapMarkDecorationsDirty();

    @Nullable
    @Invoker("nextUpdatePacket")
    Packet<?> easymapNextUpdatePacket(int mapId);

    @Accessor("dirtyData")
    boolean easymapGetDirtyData();

    @Accessor("dirtyData")
    void easymapSetDirtyData(boolean dirtyData);

    @Accessor("dirtyDecorations")
    boolean easymapGetDirtyDecorations();

    @Accessor("dirtyDecorations")
    void easymapSetDirtyDecorations(boolean dirtyDecorations);

    @Accessor("minDirtyX")
    int easymapGetMinDirtyX();

    @Accessor("minDirtyX")
    void easymapSetMinDirtyX(int minDirtyX);

    @Accessor("minDirtyY")
    int easymapGetMinDirtyY();

    @Accessor("minDirtyY")
    void easymapSetMinDirtyY(int minDirtyY);

    @Accessor("maxDirtyX")
    int easymapGetMaxDirtyX();

    @Accessor("maxDirtyX")
    void easymapSetMaxDirtyX(int maxDirtyX);

    @Accessor("maxDirtyY")
    int easymapGetMaxDirtyY();

    @Accessor("maxDirtyY")
    void easymapSetMaxDirtyY(int maxDirtyY);

    @Accessor("tick")
    int easymapGetTick();

    @Accessor("tick")
    void easymapSetTick(int tick);
}
