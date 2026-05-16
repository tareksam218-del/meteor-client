/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SusChunk — detects player-disturbed amethyst geodes using two signals:
 *
 *  1. Growth ratio  — budding blocks (permanent) vs crystal growth stages.
 *                     Looted geodes have bare budding blocks with no growth.
 *  2. Light score   — amethyst emits block light (cluster=5, large=4, med=2, small=1).
 *                     Stripped geodes go dark underground.
 *
 * Sensitivity 1–10:
 *   1 = very sensitive, flags almost any disruption (many results)
 *  10 = very strict, only flags heavily looted geodes  (fewer results)
 *
 * Simulation distance 2–10 chunks:
 *   Only scans chunks within this many chunks of the player.
 */
public class SusChunk extends Module {

    private final SettingGroup sgDetection = settings.getDefaultGroup();
    private final SettingGroup sgRender    = settings.createGroup("Render");

    // ── Detection settings ────────────────────────────────────────────────────

    private final Setting<Double> sensitivity = sgDetection.add(new DoubleSetting.Builder()
        .name("sensitivity")
        .description("1 = most sensitive (more results), 10 = strictest (fewer results). "
            + "Controls both growth ratio and light thresholds automatically.")
        .defaultValue(5.0)
        .min(1.0).sliderRange(1.0, 10.0)
        .decimalPlaces(1)
        
        .build()
    );

    private final Setting<Integer> simulationDistance = sgDetection.add(new IntSetting.Builder()
        .name("simulation-distance")
        .description("How many chunks around you to scan (2–10). Larger = more coverage but heavier.")
        .defaultValue(5)
        .min(2).sliderRange(2, 10)
        .build()
    );

    private final Setting<Integer> minBuddingBlocks = sgDetection.add(new IntSetting.Builder()
        .name("min-budding-blocks")
        .description("Minimum BUDDING_AMETHYST blocks required to count as a real geode.")
        .defaultValue(3)
        .min(1).sliderMax(20)
        .build()
    );

    private final Setting<Integer> minDistance = sgDetection.add(new IntSetting.Builder()
        .name("min-distance-from-spawn")
        .description("Minimum distance from 0,0 before chunks are scanned.")
        .defaultValue(200)
        .min(0).sliderMax(10000)
        .build()
    );

    private final Setting<Boolean> notify = sgDetection.add(new BoolSetting.Builder()
        .name("notify")
        .description("Sends a chat message when a disturbed geode is found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> clearOnDisable = sgDetection.add(new BoolSetting.Builder()
        .name("clear-on-disable")
        .defaultValue(false)
        .build()
    );

    // ── Render settings ───────────────────────────────────────────────────────

    private final Setting<SettingColor> chunkColor = sgRender.add(new ColorSetting.Builder()
        .name("chunk-color")
        .defaultValue(new SettingColor(160, 80, 255, 220))
        .build()
    );

    private final Setting<SettingColor> fillColor = sgRender.add(new ColorSetting.Builder()
        .name("fill-color")
        .defaultValue(new SettingColor(130, 50, 220, 30))
        .build()
    );

    private final Setting<Boolean> renderTracer = sgRender.add(new BoolSetting.Builder()
        .name("tracer")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> tracerColor = sgRender.add(new ColorSetting.Builder()
        .name("tracer-color")
        .defaultValue(new SettingColor(179, 100, 255, 180))
        .visible(renderTracer::get)
        .build()
    );

    private final Setting<Integer> renderHeight = sgRender.add(new IntSetting.Builder()
        .name("render-height")
        .defaultValue(5)
        .min(1).sliderMax(32)
        .build()
    );

    // ── State ─────────────────────────────────────────────────────────────────
    private final CopyOnWriteArrayList<Entry> found = new CopyOnWriteArrayList<>();

    public SusChunk() {
        super(Categories.Render, "sus-chunk",
            "Detects player-disturbed amethyst geodes via growth ratio and light level scanning.");
    }

    @Override
    public void onDeactivate() {
        if (clearOnDisable.get()) found.clear();
    }

    // ── Sensitivity → thresholds ──────────────────────────────────────────────
    // Sensitivity 1  → growth threshold 3.5, light threshold 3.0  (catches almost anything)
    // Sensitivity 5  → growth threshold 1.8, light threshold 1.5  (balanced default)
    // Sensitivity 10 → growth threshold 0.5, light threshold 0.4  (only heavily looted)
    private double growthThreshold() {
        // Linearly interpolate: sens 1 → 3.5, sens 10 → 0.5
        return 0.5 + (sensitivity.get() - 1) * (3.0 / 9.0);
    }

    private double lightThreshold() {
        // Linearly interpolate: sens 1 → 3.0, sens 10 → 0.4
        return 0.4 + (sensitivity.get() - 1) * (2.6 / 9.0);
    }

    // ── Chunk scan ────────────────────────────────────────────────────────────
    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (mc.player == null || mc.level == null) return;

        LevelChunk chunk = event.chunk();
        ChunkPos   cpos  = chunk.getPos();

        // ── Simulation distance gate ──────────────────────────────────────────
        // Only scan chunks within simulationDistance chunks of the player
        int playerChunkX = mc.player.chunkPosition().x;
        int playerChunkZ = mc.player.chunkPosition().z;
        int dx = Math.abs(cpos.x - playerChunkX);
        int dz = Math.abs(cpos.z - playerChunkZ);
        if (dx > simulationDistance.get() || dz > simulationDistance.get()) return;

        // ── Spawn distance gate ───────────────────────────────────────────────
        double cx = cpos.x * 16.0, cz = cpos.z * 16.0;
        if (Math.sqrt(cx * cx + cz * cz) < minDistance.get()) return;

        // Already flagged?
        for (Entry e : found) if (e.pos.equals(cpos)) return;

        // ── Block scan ────────────────────────────────────────────────────────
        int buddingCount  = 0;
        int clusterCount  = 0;
        int largeBudCount = 0;
        int medBudCount   = 0;
        int smallBudCount = 0;
        int lightTotal    = 0;
        int lightSamples  = 0;

        int minY = mc.level.getMinBuildHeight();

        for (int x = cpos.getMinBlockX(); x < cpos.getMinBlockX() + 16; x++) {
            for (int z = cpos.getMinBlockZ(); z < cpos.getMinBlockZ() + 16; z++) {
                for (int y = minY; y < 70; y++) {
                    BlockPos bp = new BlockPos(x, y, z);
                    Block b = chunk.getBlockState(bp).getBlock();

                    if      (b == Blocks.BUDDING_AMETHYST)    buddingCount++;
                    else if (b == Blocks.AMETHYST_CLUSTER)    clusterCount++;
                    else if (b == Blocks.LARGE_AMETHYST_BUD)  largeBudCount++;
                    else if (b == Blocks.MEDIUM_AMETHYST_BUD) medBudCount++;
                    else if (b == Blocks.SMALL_AMETHYST_BUD)  smallBudCount++;
                    else if (b == Blocks.AIR || b == Blocks.CAVE_AIR) {
                        lightTotal += mc.level.getBrightness(LightLayer.BLOCK, bp);
                        lightSamples++;
                    }
                }
            }
        }

        if (buddingCount < minBuddingBlocks.get()) return;

        // ── Signal 1: Growth ratio ────────────────────────────────────────────
        double totalGrowth = clusterCount   * 1.0
                           + largeBudCount  * 0.75
                           + medBudCount    * 0.5
                           + smallBudCount  * 0.25;
        double ratio = totalGrowth / buddingCount;
        boolean growthSus = ratio >= growthThreshold();

        // ── Signal 2: Light level ─────────────────────────────────────────────
        double avgLight = lightSamples > 0 ? (double) lightTotal / lightSamples : 0;
        boolean lightSus = avgLight > lightThreshold();

        if (!growthSus && !lightSus) return;

        // ── Flag it ───────────────────────────────────────────────────────────
        double centerX = cpos.x * 16.0 + 8;
        double centerZ = cpos.z * 16.0 + 8;
        double centerY = mc.level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING,
            new BlockPos((int) centerX, 0, (int) centerZ)).getY();

        String reason = growthSus && lightSus ? "growth+light"
                      : growthSus             ? "growth"
                      :                         "light";

        found.add(new Entry(cpos, ratio, avgLight, buddingCount, (int) totalGrowth,
            new Vec3(centerX, centerY, centerZ)));

        if (notify.get()) {
            ChatUtils.info("SusChunk",
                "Disturbed geode §d" + cpos.x + ", " + cpos.z
                + " §7[sens:" + sensitivity.get() + " | " + reason + "]"
                + " §7| budding: §d" + buddingCount
                + " §7| growth: §d" + (int) totalGrowth
                + " §7| ratio: §d" + String.format("%.2f", ratio)
                + " §7| light: §d" + String.format("%.2f", avgLight));
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────
    @EventHandler
    private void onRender3D(Render3DEvent event) {
        for (Entry entry : found) {
            double x1 = entry.pos.getMinBlockX(), z1 = entry.pos.getMinBlockZ();
            double x2 = x1 + 16,                  z2 = z1 + 16;
            double y  = entry.center.y;
            double h  = renderHeight.get();

            var ol = chunkColor.get();
            var fl = fillColor.get();

            event.renderer.quadHorizontal(x1, y, z1, x2, z2, fl);

            event.renderer.line(x1, y,   z1, x1, y+h, z1, ol);
            event.renderer.line(x2, y,   z1, x2, y+h, z1, ol);
            event.renderer.line(x1, y,   z2, x1, y+h, z2, ol);
            event.renderer.line(x2, y,   z2, x2, y+h, z2, ol);

            event.renderer.line(x1, y+h, z1, x2, y+h, z1, ol);
            event.renderer.line(x2, y+h, z1, x2, y+h, z2, ol);
            event.renderer.line(x2, y+h, z2, x1, y+h, z2, ol);
            event.renderer.line(x1, y+h, z2, x1, y+h, z1, ol);

            event.renderer.line(x1, y, z1, x2, y, z1, ol);
            event.renderer.line(x2, y, z1, x2, y, z2, ol);
            event.renderer.line(x2, y, z2, x1, y, z2, ol);
            event.renderer.line(x1, y, z2, x1, y, z1, ol);

            if (renderTracer.get()) {
                Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
                event.renderer.line(cam.x, cam.y, cam.z,
                    entry.center.x, y + h / 2.0, entry.center.z, tracerColor.get());
            }
        }
    }

    private record Entry(ChunkPos pos, double ratio, double avgLight,
                         int buddingCount, int growthCount, Vec3 center) {}
}
