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
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * NewChunks — only highlights old chunks with HEAVY underground player activity.
 *
 * Activity scoring (underground only, y < 64):
 *   - Air pockets in solid rock layers  → tunnels/mining
 *   - Torches / lanterns / glowstone    → player-placed lighting
 *   - Missing bedrock at floor          → bedrock mining
 *   - Storage blocks                    → chests, barrels, shulkers
 *   - Crafting / utility blocks         → crafting tables, furnaces
 *   - Cobblestone / stone bricks        → player construction
 *   - Unnatural air void ratio          → large excavation
 *
 * Only chunks that score above the activity threshold are highlighted.
 * New chunks are never shown — they clutter the screen with no useful info.
 */
public class NewChunks extends Module {

    private final SettingGroup sgDetection = settings.getDefaultGroup();
    private final SettingGroup sgRender    = settings.createGroup("Render");

    // ── Detection ─────────────────────────────────────────────────────────────
    private final Setting<Integer> activityThreshold = sgDetection.add(new IntSetting.Builder()
        .name("activity-threshold")
        .description("Minimum activity score to highlight a chunk. Higher = only the most heavily used chunks show.")
        .defaultValue(40)
        .min(10).sliderRange(10, 150)
        .build()
    );

    private final Setting<Integer> scanDepth = sgDetection.add(new IntSetting.Builder()
        .name("scan-depth")
        .description("How deep underground to scan (from y=0 downward). Higher = catches deeper bases.")
        .defaultValue(64)
        .min(16).sliderRange(16, 128)
        .build()
    );

    private final Setting<Boolean> clearOnDisable = sgDetection.add(new BoolSetting.Builder()
        .name("clear-on-disable")
        .defaultValue(true)
        .build()
    );

    // ── Render ────────────────────────────────────────────────────────────────
    private final Setting<SettingColor> activeColor = sgRender.add(new ColorSetting.Builder()
        .name("active-chunk-color")
        .description("Outline color for chunks with heavy underground activity.")
        .defaultValue(new SettingColor(80, 255, 80, 210))
        .build()
    );

    private final Setting<SettingColor> activeFill = sgRender.add(new ColorSetting.Builder()
        .name("active-chunk-fill")
        .description("Fill color for active chunks.")
        .defaultValue(new SettingColor(80, 255, 80, 20))
        .build()
    );

    private final Setting<Integer> renderHeight = sgRender.add(new IntSetting.Builder()
        .name("render-height")
        .defaultValue(5)
        .min(1).sliderMax(32)
        .build()
    );

    private final Setting<Boolean> renderTracer = sgRender.add(new BoolSetting.Builder()
        .name("tracer")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> tracerColor = sgRender.add(new ColorSetting.Builder()
        .name("tracer-color")
        .defaultValue(new SettingColor(80, 255, 80, 160))
        .visible(renderTracer::get)
        .build()
    );

    // ── State ─────────────────────────────────────────────────────────────────
    private final CopyOnWriteArrayList<ChunkEntry> activeChunks = new CopyOnWriteArrayList<>();

    public NewChunks() {
        super(Categories.Render, "new-chunks",
            "Highlights old chunks with heavy underground player activity — tunnels, lighting, storage, construction.");
    }

    @Override
    public void onDeactivate() {
        if (clearOnDisable.get()) activeChunks.clear();
    }

    // ── Chunk scan ────────────────────────────────────────────────────────────
    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        LevelChunk chunk = event.chunk();
        ChunkPos   cpos  = chunk.getPos();

        // Skip new chunks entirely — void air = freshly generated, nothing there
        for (LevelChunkSection section : chunk.getSections()) {
            if (section != null && !section.hasOnlyAir()
                && section.getStates().maybeHas(s -> s.is(Blocks.VOID_AIR))) return;
        }

        // Skip already classified
        for (ChunkEntry e : activeChunks) if (e.pos.equals(cpos)) return;

        int score = scoreUndergroundActivity(chunk);
        if (score < activityThreshold.get()) return;

        double centerX = cpos.x * 16.0 + 8;
        double centerZ = cpos.z * 16.0 + 8;
        double centerY = mc.level != null
            ? mc.level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING,
                new BlockPos((int) centerX, 0, (int) centerZ)).getY()
            : 64;

        activeChunks.add(new ChunkEntry(cpos, score, new Vec3(centerX, centerY, centerZ)));
    }

    // ── Activity scoring ──────────────────────────────────────────────────────
    private int scoreUndergroundActivity(LevelChunk chunk) {
        if (mc.level == null) return 0;

        int score    = 0;
        int minY     = mc.level.getMinBuildHeight();
        int maxScanY = Math.min(0, minY + scanDepth.get()); // only scan underground
        int startX   = chunk.getPos().getMinBlockX();
        int startZ   = chunk.getPos().getMinBlockZ();

        // Track air pocket ratio in stone layers for excavation detection
        int stoneCount  = 0;
        int airInStone  = 0;

        for (int x = startX; x < startX + 16; x++) {
            for (int z = startZ; z < startZ + 16; z++) {
                for (int y = minY; y < maxScanY; y++) {
                    BlockPos bp = new BlockPos(x, y, z);
                    var block = chunk.getBlockState(bp).getBlock();

                    // ── Mining / tunnels ──────────────────────────────────────
                    // Air surrounded by stone layers = mined tunnel
                    if (block == Blocks.AIR || block == Blocks.CAVE_AIR) {
                        // Check if this air has solid stone neighbours — indicates a tunnel
                        boolean hasStonNeighbour =
                            chunk.getBlockState(bp.north()).isSolid() ||
                            chunk.getBlockState(bp.south()).isSolid() ||
                            chunk.getBlockState(bp.east()).isSolid()  ||
                            chunk.getBlockState(bp.west()).isSolid();
                        if (hasStonNeighbour) airInStone++;
                    }

                    if (block == Blocks.STONE || block == Blocks.DEEPSLATE ||
                        block == Blocks.TUFF   || block == Blocks.ANDESITE ||
                        block == Blocks.GRANITE || block == Blocks.DIORITE) {
                        stoneCount++;
                    }

                    // ── Player lighting (high weight — very unnatural) ─────────
                    if (block == Blocks.TORCH          ||
                        block == Blocks.WALL_TORCH     ||
                        block == Blocks.LANTERN        ||
                        block == Blocks.SOUL_TORCH     ||
                        block == Blocks.SOUL_LANTERN   ||
                        block == Blocks.GLOWSTONE      ||
                        block == Blocks.SEA_LANTERN    ||
                        block == Blocks.SHROOMLIGHT    ||
                        block == Blocks.JACK_O_LANTERN) {
                        score += 6;
                    }

                    // ── Storage blocks (very high weight) ─────────────────────
                    if (block == Blocks.CHEST          ||
                        block == Blocks.TRAPPED_CHEST  ||
                        block == Blocks.BARREL         ||
                        block == Blocks.ENDER_CHEST    ||
                        block == Blocks.SHULKER_BOX    ||
                        block == Blocks.WHITE_SHULKER_BOX ||
                        block == Blocks.PURPLE_SHULKER_BOX) {
                        score += 10;
                    }

                    // ── Crafting / utility blocks ─────────────────────────────
                    if (block == Blocks.CRAFTING_TABLE ||
                        block == Blocks.FURNACE        ||
                        block == Blocks.BLAST_FURNACE  ||
                        block == Blocks.SMOKER         ||
                        block == Blocks.ANVIL          ||
                        block == Blocks.ENCHANTING_TABLE ||
                        block == Blocks.BREWING_STAND  ||
                        block == Blocks.RESPAWN_ANCHOR) {
                        score += 8;
                    }

                    // ── Player construction ───────────────────────────────────
                    if (block == Blocks.COBBLESTONE         ||
                        block == Blocks.STONE_BRICKS        ||
                        block == Blocks.CRACKED_STONE_BRICKS||
                        block == Blocks.CHISELED_STONE_BRICKS||
                        block == Blocks.OAK_PLANKS          ||
                        block == Blocks.SPRUCE_PLANKS       ||
                        block == Blocks.DARK_OAK_PLANKS     ||
                        block == Blocks.OBSIDIAN            ||
                        block == Blocks.CRYING_OBSIDIAN     ||
                        block == Blocks.REINFORCED_DEEPSLATE) {
                        score += 3;
                    }

                    // ── Beds / signs (player presence) ────────────────────────
                    if (block == Blocks.WHITE_BED  || block == Blocks.RED_BED   ||
                        block == Blocks.BLACK_BED  || block == Blocks.OAK_SIGN  ||
                        block == Blocks.OAK_WALL_SIGN) {
                        score += 5;
                    }

                    // ── Bedrock gaps at floor (mining at floor level) ─────────
                    if (y == minY && block != Blocks.BEDROCK) score += 4;

                    // ── Block light in cave air (torches nearby) ──────────────
                    if ((block == Blocks.AIR || block == Blocks.CAVE_AIR) && mc.level != null) {
                        int light = mc.level.getBrightness(LightLayer.BLOCK, bp);
                        if (light >= 8) score += 2; // strong block light = player torch nearby
                    }
                }
            }
        }

        // ── Excavation bonus ──────────────────────────────────────────────────
        // High ratio of air in stone layers = large tunnel network or room
        if (stoneCount > 0) {
            double excavationRatio = (double) airInStone / stoneCount;
            if (excavationRatio > 0.15) score += (int)(excavationRatio * 30);
        }

        return score;
    }

    // ── Render ────────────────────────────────────────────────────────────────
    @EventHandler
    private void onRender3D(Render3DEvent event) {
        var outline = activeColor.get();
        var fill    = activeFill.get();

        for (ChunkEntry entry : activeChunks) {
            double x1 = entry.pos.getMinBlockX(), z1 = entry.pos.getMinBlockZ();
            double x2 = x1 + 16,                  z2 = z1 + 16;
            double y  = entry.center.y;
            double h  = renderHeight.get();

            if (fill.a > 0)
                event.renderer.quadHorizontal(x1, y, z1, x2, z2, fill);

            if (outline.a > 0) {
                event.renderer.line(x1, y,   z1, x2, y,   z1, outline);
                event.renderer.line(x2, y,   z1, x2, y,   z2, outline);
                event.renderer.line(x2, y,   z2, x1, y,   z2, outline);
                event.renderer.line(x1, y,   z2, x1, y,   z1, outline);

                event.renderer.line(x1, y+h, z1, x2, y+h, z1, outline);
                event.renderer.line(x2, y+h, z1, x2, y+h, z2, outline);
                event.renderer.line(x2, y+h, z2, x1, y+h, z2, outline);
                event.renderer.line(x1, y+h, z2, x1, y+h, z1, outline);

                event.renderer.line(x1, y, z1, x1, y+h, z1, outline);
                event.renderer.line(x2, y, z1, x2, y+h, z1, outline);
                event.renderer.line(x1, y, z2, x1, y+h, z2, outline);
                event.renderer.line(x2, y, z2, x2, y+h, z2, outline);
            }

            if (renderTracer.get()) {
                Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
                event.renderer.line(cam.x, cam.y, cam.z,
                    entry.center.x, y + h / 2.0, entry.center.z, tracerColor.get());
            }
        }
    }

    private record ChunkEntry(ChunkPos pos, int score, Vec3 center) {}
}
