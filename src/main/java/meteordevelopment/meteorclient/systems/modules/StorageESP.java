/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.renderer.MeshBuilder;
import meteordevelopment.meteorclient.renderer.MeshRenderer;
import meteordevelopment.meteorclient.renderer.MeteorRenderPipelines;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.MeshBuilderVertexConsumerProvider;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.SimpleBlockRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.render.postprocess.PostProcessShaders;
import meteordevelopment.meteorclient.utils.world.Dir;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class StorageESP extends Module {

    // ── Setting groups ────────────────────────────────────────────────────────
    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgDistance = settings.createGroup("Distance & Fade");
    private final SettingGroup sgTracers  = settings.createGroup("Tracers");
    private final SettingGroup sgColors   = settings.createGroup("Colors");
    private final SettingGroup sgOpened   = settings.createGroup("Opened Blocks");

    // ── General ───────────────────────────────────────────────────────────────
    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Rendering mode.")
        .defaultValue(Mode.Box)
        .build()
    );

    private final Setting<List<BlockEntityType<?>>> storageBlocks = sgGeneral.add(new StorageBlockListSetting.Builder()
        .name("storage-blocks")
        .description("Which storage blocks to highlight.")
        .defaultValue(StorageBlockListSetting.STORAGE_BLOCKS)
        .build()
    );

    public final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Whether to render the outline, fill, or both.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    public final Setting<Integer> fillOpacity = sgGeneral.add(new IntSetting.Builder()
        .name("fill-opacity")
        .description("Opacity of the box fill (0 = invisible, 255 = solid).")
        .visible(() -> shapeMode.get() != ShapeMode.Lines)
        .defaultValue(40)
        .range(0, 255).sliderMax(255)
        .build()
    );

    public final Setting<Integer> outlineWidth = sgGeneral.add(new IntSetting.Builder()
        .name("outline-width")
        .description("Thickness of the outline in shader mode.")
        .visible(() -> mode.get() == Mode.Shader)
        .defaultValue(2)
        .range(1, 10).sliderRange(1, 6)
        .build()
    );

    public final Setting<Double> glowMultiplier = sgGeneral.add(new DoubleSetting.Builder()
        .name("glow-multiplier")
        .description("Intensity of the glow in shader mode.")
        .visible(() -> mode.get() == Mode.Shader)
        .defaultValue(3.5)
        .min(0).sliderMax(10)
        .decimalPlaces(1)
        .build()
    );

    // ── Distance & Fade ───────────────────────────────────────────────────────
    private final Setting<Integer> maxRenderDistance = sgDistance.add(new IntSetting.Builder()
        .name("max-render-distance")
        .description("Maximum distance in blocks to render storage ESP. Push this high to see far into the world.")
        .defaultValue(256)
        .min(16).sliderRange(16, 1024)
        .build()
    );

    private final Setting<Boolean> fadeEnabled = sgDistance.add(new BoolSetting.Builder()
        .name("fade")
        .description("Fades out blocks that are very close to you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> fadeDistance = sgDistance.add(new DoubleSetting.Builder()
        .name("fade-start")
        .description("Distance at which close blocks start to fade out.")
        .visible(fadeEnabled::get)
        .defaultValue(6)
        .min(0).sliderMax(20)
        .build()
    );

    private final Setting<Boolean> fadeAtMax = sgDistance.add(new BoolSetting.Builder()
        .name("fade-at-max-distance")
        .description("Gradually fades blocks as they approach the max render distance.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> fadeAtMaxStart = sgDistance.add(new IntSetting.Builder()
        .name("far-fade-start")
        .description("How many blocks before max distance the far fade begins.")
        .visible(fadeAtMax::get)
        .defaultValue(32)
        .min(4).sliderRange(4, 128)
        .build()
    );

    // ── Tracers ───────────────────────────────────────────────────────────────
    private final Setting<Boolean> tracers = sgTracers.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draws lines to storage blocks.")
        .defaultValue(false)
        .build()
    );

    private final Setting<TracerMode> tracerMode = sgTracers.add(new EnumSetting.Builder<TracerMode>()
        .name("tracer-mode")
        .description("Always draw tracers, or only beyond a certain distance.")
        .defaultValue(TracerMode.Always)
        .visible(tracers::get)
        .build()
    );

    private final Setting<Integer> tracerMinDistance = sgTracers.add(new IntSetting.Builder()
        .name("tracer-min-distance")
        .description("Only draw tracers to blocks farther than this distance.")
        .visible(() -> tracers.get() && tracerMode.get() == TracerMode.BeyondDistance)
        .defaultValue(32)
        .min(1).sliderRange(1, 256)
        .build()
    );

    private final Setting<Boolean> tracerMatchColor = sgTracers.add(new BoolSetting.Builder()
        .name("tracer-match-color")
        .description("Tracers use the same color as the block type. If off, uses the tracer color below.")
        .defaultValue(true)
        .visible(tracers::get)
        .build()
    );

    private final Setting<SettingColor> tracerColor = sgTracers.add(new ColorSetting.Builder()
        .name("tracer-color")
        .defaultValue(new SettingColor(179, 127, 255, 180))
        .visible(() -> tracers.get() && !tracerMatchColor.get())
        .build()
    );

    private final Setting<Integer> tracerOpacity = sgTracers.add(new IntSetting.Builder()
        .name("tracer-opacity")
        .description("Opacity of tracer lines.")
        .defaultValue(140)
        .range(0, 255).sliderMax(255)
        .visible(tracers::get)
        .build()
    );

    // ── Colors ────────────────────────────────────────────────────────────────
    private final Setting<SettingColor> chest = sgColors.add(new ColorSetting.Builder()
        .name("chest")
        .description("Color for regular chests.")
        .defaultValue(new SettingColor(255, 160, 0, 255))
        .build()
    );

    private final Setting<SettingColor> trappedChest = sgColors.add(new ColorSetting.Builder()
        .name("trapped-chest")
        .description("Color for trapped chests.")
        .defaultValue(new SettingColor(255, 40, 40, 255))
        .build()
    );

    private final Setting<SettingColor> barrel = sgColors.add(new ColorSetting.Builder()
        .name("barrel")
        .description("Color for barrels.")
        .defaultValue(new SettingColor(210, 130, 0, 255))
        .build()
    );

    private final Setting<SettingColor> shulker = sgColors.add(new ColorSetting.Builder()
        .name("shulker-box")
        .description("Color for shulker boxes.")
        .defaultValue(new SettingColor(179, 127, 255, 255))
        .build()
    );

    private final Setting<SettingColor> enderChest = sgColors.add(new ColorSetting.Builder()
        .name("ender-chest")
        .description("Color for ender chests.")
        .defaultValue(new SettingColor(100, 0, 220, 255))
        .build()
    );

    private final Setting<SettingColor> furnace = sgColors.add(new ColorSetting.Builder()
        .name("furnace")
        .description("Color for furnaces, blast furnaces, and smokers.")
        .defaultValue(new SettingColor(160, 160, 160, 255))
        .build()
    );

    private final Setting<SettingColor> hopper = sgColors.add(new ColorSetting.Builder()
        .name("hopper")
        .description("Color for hoppers.")
        .defaultValue(new SettingColor(100, 100, 100, 255))
        .build()
    );

    private final Setting<SettingColor> dispenser = sgColors.add(new ColorSetting.Builder()
        .name("dispenser-dropper")
        .description("Color for dispensers and droppers.")
        .defaultValue(new SettingColor(130, 130, 130, 255))
        .build()
    );

    private final Setting<SettingColor> brewingStand = sgColors.add(new ColorSetting.Builder()
        .name("brewing-stand")
        .description("Color for brewing stands.")
        .defaultValue(new SettingColor(80, 200, 120, 255))
        .build()
    );

    private final Setting<SettingColor> other = sgColors.add(new ColorSetting.Builder()
        .name("other")
        .description("Color for all other storage types.")
        .defaultValue(new SettingColor(140, 140, 140, 255))
        .build()
    );

    // ── Opened blocks ─────────────────────────────────────────────────────────
    private final Setting<Boolean> hideOpened = sgOpened.add(new BoolSetting.Builder()
        .name("hide-opened")
        .description("Don't render blocks you've already opened.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> openedColor = sgOpened.add(new ColorSetting.Builder()
        .name("opened-color")
        .description("Color for opened blocks. Set alpha to 0 to use original color at reduced opacity.")
        .defaultValue(new SettingColor(203, 90, 203, 0))
        .visible(() -> !hideOpened.get())
        .build()
    );

    private final Setting<Integer> openedOpacity = sgOpened.add(new IntSetting.Builder()
        .name("opened-opacity")
        .description("Opacity multiplier for opened blocks when not using a custom color.")
        .visible(() -> !hideOpened.get() && openedColor.get().a == 0)
        .defaultValue(80)
        .range(0, 255).sliderMax(255)
        .build()
    );

    // ── State ─────────────────────────────────────────────────────────────────
    private final Set<BlockPos> interactedBlocks = new HashSet<>();
    private final Color lineColor = new Color(0, 0, 0, 0);
    private final Color sideColor = new Color(0, 0, 0, 0);
    private boolean render;
    private int count;

    private final MeshBuilder mesh;
    private final MeshBuilderVertexConsumerProvider vertexConsumerProvider;

    public StorageESP() {
        super(Categories.Render, "storage-esp",
            "Renders storage blocks at extended range. Highly customizable per block type.");
        mesh = new MeshBuilder(MeteorRenderPipelines.WORLD_COLORED);
        vertexConsumerProvider = new MeshBuilderVertexConsumerProvider(mesh);
    }

    // ── GUI widget ────────────────────────────────────────────────────────────
    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WButton clear = list.add(theme.button("Clear Opened Cache")).expandX().widget();
        clear.action = interactedBlocks::clear;
        return list;
    }

    // ── Block interact tracking ───────────────────────────────────────────────
    @EventHandler
    private void onBlockInteract(InteractBlockEvent event) {
        BlockPos pos = event.result.getBlockPos();
        BlockEntity blockEntity = mc.level.getBlockEntity(pos);
        if (blockEntity == null) return;

        interactedBlocks.add(pos);

        if (blockEntity instanceof ChestBlockEntity chestBE) {
            BlockState state = chestBE.getBlockState();
            ChestType chestType = state.getValue(ChestBlock.TYPE);
            if (chestType == ChestType.LEFT || chestType == ChestType.RIGHT) {
                Direction facing = state.getValue(ChestBlock.FACING);
                BlockPos other = pos.relative(chestType == ChestType.LEFT
                    ? facing.getClockWise() : facing.getCounterClockWise());
                interactedBlocks.add(other);
            }
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────
    @EventHandler
    private void onRender(Render3DEvent event) {
        count = 0;
        double maxDist = maxRenderDistance.get();
        double maxDistSq = maxDist * maxDist;

        for (BlockEntity blockEntity : Utils.blockEntities()) {
            BlockPos bp = blockEntity.getBlockPos();

            // ── Extended distance gate ────────────────────────────────────────
            double distSq = PlayerUtils.squaredDistanceTo(
                bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5);
            if (distSq > maxDistSq) continue;

            double dist = Math.sqrt(distSq);

            // ── Block type → color ────────────────────────────────────────────
            resolveColor(blockEntity);
            if (!render) continue;

            // ── Opened block handling ─────────────────────────────────────────
            boolean interacted = interactedBlocks.contains(bp);
            if (interacted && hideOpened.get()) continue;

            if (interacted) {
                if (openedColor.get().a > 0) {
                    lineColor.set(openedColor.get());
                    sideColor.set(openedColor.get());
                    sideColor.a = fillOpacity.get();
                } else {
                    // Dim original color
                    lineColor.a = openedOpacity.get();
                    sideColor.a = Math.min(fillOpacity.get(), openedOpacity.get());
                }
            }

            // ── Alpha calculation ─────────────────────────────────────────────
            double alpha = 1.0;

            // Near fade
            if (fadeEnabled.get() && dist <= fadeDistance.get()) {
                alpha = distSq / (fadeDistance.get() * fadeDistance.get());
                if (alpha < 0.075) continue;
            }

            // Far fade
            if (fadeAtMax.get()) {
                double fadeStart = maxDist - fadeAtMaxStart.get();
                if (dist > fadeStart) {
                    alpha = Math.min(alpha, (maxDist - dist) / (double) fadeAtMaxStart.get());
                    if (alpha < 0.02) continue;
                }
            }

            // Apply alpha
            int prevLineA = lineColor.a;
            int prevSideA = sideColor.a;
            lineColor.a = (int) (lineColor.a * alpha);
            sideColor.a = (int) (sideColor.a * alpha);

            // ── Tracers ───────────────────────────────────────────────────────
            if (tracers.get()) {
                boolean shouldTrace = tracerMode.get() == TracerMode.Always
                    || (tracerMode.get() == TracerMode.BeyondDistance && dist > tracerMinDistance.get());

                if (shouldTrace) {
                    Color tc;
                    if (tracerMatchColor.get()) {
                        tc = new Color(lineColor).a(tracerOpacity.get());
                    } else {
                        tc = new Color(tracerColor.get()).a(tracerOpacity.get());
                    }
                    event.renderer.line(
                        RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                        bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5, tc);
                }
            }

            // ── Box / Shader render ───────────────────────────────────────────
            if (mode.get() == Mode.Box) {
                renderBox(event, blockEntity);
            } else if (mode.get() == Mode.Shader) {
                if (count == 0) mesh.begin();
                renderShader(event, blockEntity);
            }

            lineColor.a = prevLineA;
            sideColor.a = prevSideA;
            count++;
        }

        if (mode.get() == Mode.Shader && count > 0) {
            MeshRenderer.begin()
                .attachments(PostProcessShaders.STORAGE_OUTLINE.framebuffer)
                .clearColor(Color.CLEAR)
                .pipeline(MeteorRenderPipelines.WORLD_COLORED)
                .mesh(mesh, event.matrices)
                .end();
            PostProcessShaders.STORAGE_OUTLINE.render();
        }
    }

    // ── Color resolution ──────────────────────────────────────────────────────
    private void resolveColor(BlockEntity be) {
        render = false;
        if (!storageBlocks.get().contains(be.getType())) return;

        Color c;
        if      (be instanceof TrappedChestBlockEntity)       c = trappedChest.get();
        else if (be instanceof ChestBlockEntity)              c = chest.get();
        else if (be instanceof BarrelBlockEntity)             c = barrel.get();
        else if (be instanceof ShulkerBoxBlockEntity)         c = shulker.get();
        else if (be instanceof EnderChestBlockEntity)         c = enderChest.get();
        else if (be instanceof BrewingStandBlockEntity)       c = brewingStand.get();
        else if (be instanceof HopperBlockEntity)             c = hopper.get();
        else if (be instanceof DispenserBlockEntity)          c = dispenser.get();
        else if (be instanceof AbstractFurnaceBlockEntity)    c = furnace.get();
        else if (be instanceof ChiseledBookShelfBlockEntity
              || be instanceof CrafterBlockEntity
              || be instanceof DecoratedPotBlockEntity)       c = other.get();
        else return;

        lineColor.set(c);
        sideColor.set(c);
        sideColor.a = fillOpacity.get();
        render = true;
    }

    // ── Box rendering ─────────────────────────────────────────────────────────
    private void renderBox(Render3DEvent event, BlockEntity be) {
        double x1 = be.getBlockPos().getX();
        double y1 = be.getBlockPos().getY();
        double z1 = be.getBlockPos().getZ();
        double x2 = x1 + 1, y2 = y1 + 1, z2 = z1 + 1;

        int excludeDir = 0;
        if (be instanceof ChestBlockEntity) {
            BlockState state = mc.level.getBlockState(be.getBlockPos());
            if ((state.getBlock() == Blocks.CHEST || state.getBlock() == Blocks.TRAPPED_CHEST)
                    && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                excludeDir = Dir.get(ChestBlock.getConnectedDirection(state));
            }
        }

        if (be instanceof ChestBlockEntity || be instanceof EnderChestBlockEntity) {
            double a = 1.0 / 16.0;
            if (Dir.isNot(excludeDir, Dir.WEST))  x1 += a;
            if (Dir.isNot(excludeDir, Dir.NORTH)) z1 += a;
            if (Dir.isNot(excludeDir, Dir.EAST))  x2 -= a;
            y2 -= a * 2;
            if (Dir.isNot(excludeDir, Dir.SOUTH)) z2 -= a;
        }

        event.renderer.box(x1, y1, z1, x2, y2, z2, sideColor, lineColor, shapeMode.get(), excludeDir);
    }

    // ── Shader rendering ──────────────────────────────────────────────────────
    private void renderShader(Render3DEvent event, BlockEntity be) {
        vertexConsumerProvider.setColor(lineColor);
        SimpleBlockRenderer.renderWithBlockEntity(be, event.tickDelta, vertexConsumerProvider);
    }

    @Override
    public String getInfoString() {
        return Integer.toString(count);
    }

    public boolean isShader() {
        return isActive() && mode.get() == Mode.Shader;
    }

    public enum Mode       { Box, Shader }
    public enum TracerMode { Always, BeyondDistance }
}
