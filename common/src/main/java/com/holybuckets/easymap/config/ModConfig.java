package com.holybuckets.easymap.config;

import com.holybuckets.foundation.GeneralConfig;
import com.holybuckets.foundation.HBUtil;
import com.holybuckets.foundation.event.EventRegistrar;
import com.holybuckets.easymap.LoggerProject;
import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.balm.api.event.EventPriority;
import net.blay09.mods.balm.api.event.server.ServerStartingEvent;
import net.blay09.mods.balm.api.event.server.ServerStoppedEvent;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.structure.Structure;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ModConfig {

    private static final String CLASS_ID = "016";
    public static final int VANILLA_MAX_SCALE = 4;

    private static ModConfig INSTANCE;

    private final Set<ResourceLocation> revealableStructures = new HashSet<>();
    private final Set<Structure> revealableStructureObjects = new HashSet<>();
    private final Map<Structure, ResourceLocation> structureToLocationMap = new HashMap<>();

    private Registry<Structure> structureRegistry;
    private boolean hydrated = false;

    public static ModConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ModConfig();
        }
        return INSTANCE;
    }

    private ModConfig() {
    }

    public static void init(EventRegistrar registrar) {
        INSTANCE = ModConfig.getInstance();
        registrar.registerOnBeforeServerStarted(ModConfig::onBeforeServerStarted, EventPriority.High);
        registrar.registerOnServerStopped(ModConfig::onServerStopped, EventPriority.Low);
    }

    public static EasyMapsConfig getBalmConfig() {
        EasyMapsConfig config = Balm.getConfig().getActiveConfig(EasyMapsConfig.class);
        return (config != null) ? config : new EasyMapsConfig();
    }

    public static boolean isDevMode() {
        return getBalmConfig().devMode;
    }

    public static int getMaxIconsPerMap() {
        return Math.max(0, getBalmConfig().maxIconsPerMap);
    }

    public static boolean revealFillsTerrain() {
        return getBalmConfig().revealFillsTerrain;
    }

    public static boolean primeEdgeChunks() {
        return getBalmConfig().primeEdgeChunks;
    }

    public static int getPrimeChunkStride() {
        return Math.max(1, getBalmConfig().primeChunkStride);
    }

    public static int getEffectiveMapScale(int storedScale) {
        EasyMapsConfig config = getBalmConfig();
        int ceiling = Math.max(0, Math.min(config.maxEffectiveMapScale, 7));

        List<Integer> table = config.mapScaleTable;
        if (table == null || storedScale < 0 || storedScale >= table.size()) {
            return Math.max(0, Math.min(storedScale, ceiling));
        }

        Integer mapped = table.get(storedScale);
        if (mapped == null) return Math.max(0, Math.min(storedScale, ceiling));
        return Math.max(0, Math.min(mapped, ceiling));
    }

    public boolean isHydrated() {
        return hydrated;
    }

    public Set<ResourceLocation> getRevealableStructures() {
        return Collections.unmodifiableSet(revealableStructures);
    }

    public Set<Structure> getRevealableStructureObjects() {
        return Collections.unmodifiableSet(revealableStructureObjects);
    }

    public boolean isRevealable(ResourceLocation structureLoc) {
        return structureLoc != null && revealableStructures.contains(structureLoc);
    }

    public ResourceLocation getStructureLoc(Structure structure) {
        return structureToLocationMap.get(structure);
    }

    @Nullable
    public Registry<Structure> getStructureRegistry() {
        return structureRegistry;
    }

    @Nullable
    public Holder<Structure> getHolder(ResourceLocation loc) {
        if (loc == null || structureRegistry == null) return null;
        return structureRegistry.getHolder(ResourceKey.create(Registries.STRUCTURE, loc)).orElse(null);
    }

    @Nullable
    public HolderSet<Structure> getRevealableHolderSet() {
        if (structureRegistry == null || revealableStructures.isEmpty()) return null;
        List<Holder<Structure>> holders = new ArrayList<>(revealableStructures.size());
        for (ResourceLocation loc : revealableStructures) {
            Holder<Structure> holder = getHolder(loc);
            if (holder != null) holders.add(holder);
        }
        return holders.isEmpty() ? null : HolderSet.direct(holders);
    }

    public void reload() {
        hydrate(getBalmConfig());
    }

    private void onBeforeServerStarted() {
        hydrate(getBalmConfig());
        LoggerProject.logInfo("016001",
            "Easy Maps revealable structures hydrated: " + revealableStructures.size()
            + " of " + getBalmConfig().revealableStructures.size() + " configured");
    }

    private void hydrate(EasyMapsConfig activeConfig) {
        hydrated = false;
        revealableStructures.clear();
        revealableStructureObjects.clear();
        structureToLocationMap.clear();

        Registry<Structure> registry = resolveStructureRegistry();
        this.structureRegistry = registry;
        if (registry == null) {
            LoggerProject.logError("016002",
                "Structure registry unavailable, no structures will be revealable");
            return;
        }

        List<String> configured = activeConfig.revealableStructures;
        if (configured == null) return;

        for (String structId : configured) {
            if (structId == null || structId.isBlank()) continue;

            ResourceLocation loc;
            try {
                loc = HBUtil.LOC(structId.trim());
            } catch (Exception e) {
                LoggerProject.logWarning("016003",
                    "Invalid structure id in revealableStructures: '" + structId + "'. " + e.getMessage());
                continue;
            }

            if (!registry.containsKey(loc)) {
                LoggerProject.logWarning("016004",
                    "Structure '" + loc + "' in revealableStructures is not in the structure registry, skipping");
                continue;
            }

            revealableStructures.add(loc);
            Structure structure = registry.get(loc);
            if (structure != null) {
                revealableStructureObjects.add(structure);
                structureToLocationMap.put(structure, loc);
            }
        }

        hydrated = true;

        if (isDevMode()) {
            LoggerProject.logDebug("016005", "Revealable structures: " + revealableStructures);
        }
    }

    @Nullable
    private Registry<Structure> resolveStructureRegistry() {
        MinecraftServer server = GeneralConfig.getInstance().getServer();
        if (server == null) return null;
        try {
            return server.registryAccess().registryOrThrow(Registries.STRUCTURE);
        } catch (Exception e) {
            LoggerProject.logError("016006", "Failed to access structure registry: " + e.getMessage());
            return null;
        }
    }

    private void onServerStopped() {
        structureRegistry = null;
        revealableStructures.clear();
        revealableStructureObjects.clear();
        structureToLocationMap.clear();
        INSTANCE = null;
    }

    private static void onBeforeServerStarted(ServerStartingEvent event) {
        getInstance().onBeforeServerStarted();
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        getInstance().onServerStopped();
    }
}
