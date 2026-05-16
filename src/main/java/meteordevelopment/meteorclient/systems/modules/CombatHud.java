/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.hud.elements;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.ObjectIntImmutablePair;
import it.unimi.dsi.fastutil.objects.ObjectIntPair;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.DisplayItemUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.joml.Matrix4fStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class CombatHud extends HudElement {
    public static final HudElementInfo<CombatHud> INFO = new HudElementInfo<>(
        Hud.GROUP, "combat", "Displays information about your combat target.", CombatHud::new
    );

    // ── Layout constants ──────────────────────────────────────────────────────
    private static final double PAD           = 6;
    private static final double SECTION_GAP   = 4;
    private static final double BAR_HEIGHT    = 6;
    private static final double BAR_RADIUS    = 2;   // visual only – quad approx
    private static final double DIVIDER_H     = 1;

    // ── Colours ───────────────────────────────────────────────────────────────
    private static final Color GREEN  = new Color(80, 220, 120, 255);
    private static final Color RED    = new Color(220, 70,  70,  255);
    private static final Color DARK   = new Color(0,   0,   0,   180);
    private static final Color DIMMED = new Color(100, 100, 100, 180);

    // ── Setting groups ────────────────────────────────────────────────────────
    private final SettingGroup sgGeneral      = settings.getDefaultGroup();
    private final SettingGroup sgEnchantments = settings.createGroup("Enchantments");
    private final SettingGroup sgHealth       = settings.createGroup("Health");
    private final SettingGroup sgDistance     = settings.createGroup("Distance");
    private final SettingGroup sgPing         = settings.createGroup("Ping");
    private final SettingGroup sgScale        = settings.createGroup("Scale");
    private final SettingGroup sgBackground   = settings.createGroup("Background");

    // General
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Range to target players.")
        .defaultValue(100)
        .min(1).sliderMax(200)
        .build()
    );

    // Health gradient
    private final Setting<SettingColor> healthColor1 = sgHealth.add(new ColorSetting.Builder()
        .name("health-low")
        .description("Bar colour at low health.")
        .defaultValue(new SettingColor(220, 60, 60))
        .build()
    );

    private final Setting<SettingColor> healthColor2 = sgHealth.add(new ColorSetting.Builder()
        .name("health-mid")
        .description("Bar colour at mid health.")
        .defaultValue(new SettingColor(220, 160, 40))
        .build()
    );

    private final Setting<SettingColor> healthColor3 = sgHealth.add(new ColorSetting.Builder()
        .name("health-high")
        .description("Bar colour at high health.")
        .defaultValue(new SettingColor(60, 210, 100))
        .build()
    );

    // Enchantments
    private final Setting<Set<ResourceKey<Enchantment>>> displayedEnchantments = sgEnchantments.add(
        new EnchantmentListSetting.Builder()
            .name("displayed-enchantments")
            .description("Enchantments shown under armor items.")
            .vanillaDefaults()
            .build()
    );

    private final Setting<SettingColor> enchantmentTextColor = sgEnchantments.add(new ColorSetting.Builder()
        .name("enchantment-color")
        .defaultValue(new SettingColor(200, 200, 200))
        .build()
    );

    // Ping
    private final Setting<Boolean> displayPing = sgPing.add(new BoolSetting.Builder()
        .name("ping")
        .description("Shows the target's ping.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> pingColor1 = sgPing.add(new ColorSetting.Builder()
        .name("ping-good")
        .description("Colour when ping is below 75 ms.")
        .defaultValue(new SettingColor(60, 210, 100))
        .visible(displayPing::get)
        .build()
    );

    private final Setting<SettingColor> pingColor2 = sgPing.add(new ColorSetting.Builder()
        .name("ping-medium")
        .description("Colour when ping is 75–200 ms.")
        .defaultValue(new SettingColor(220, 160, 40))
        .visible(displayPing::get)
        .build()
    );

    private final Setting<SettingColor> pingColor3 = sgPing.add(new ColorSetting.Builder()
        .name("ping-bad")
        .description("Colour when ping is above 200 ms.")
        .defaultValue(new SettingColor(220, 60, 60))
        .visible(displayPing::get)
        .build()
    );

    // Distance
    private final Setting<Boolean> displayDistance = sgDistance.add(new BoolSetting.Builder()
        .name("distance")
        .description("Shows the distance to the target.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> distColor1 = sgDistance.add(new ColorSetting.Builder()
        .name("distance-close")
        .description("Colour when target is within 10 blocks.")
        .defaultValue(new SettingColor(220, 60, 60))
        .visible(displayDistance::get)
        .build()
    );

    private final Setting<SettingColor> distColor2 = sgDistance.add(new ColorSetting.Builder()
        .name("distance-medium")
        .description("Colour when target is 10–50 blocks away.")
        .defaultValue(new SettingColor(220, 160, 40))
        .visible(displayDistance::get)
        .build()
    );

    private final Setting<SettingColor> distColor3 = sgDistance.add(new ColorSetting.Builder()
        .name("distance-far")
        .description("Colour when target is beyond 50 blocks.")
        .defaultValue(new SettingColor(60, 210, 100))
        .visible(displayDistance::get)
        .build()
    );

    // Scale
    public final Setting<Boolean> customScale = sgScale.add(new BoolSetting.Builder()
        .name("custom-scale")
        .defaultValue(false)
        .onChanged(_ -> calculateSize())
        .build()
    );

    public final Setting<Double> scale = sgScale.add(new DoubleSetting.Builder()
        .name("scale")
        .visible(customScale::get)
        .defaultValue(2)
        .onChanged(_ -> calculateSize())
        .min(0.5).sliderRange(0.5, 3)
        .build()
    );

    // Background
    public final Setting<Boolean> background = sgBackground.add(new BoolSetting.Builder()
        .name("background")
        .defaultValue(true)
        .build()
    );

    public final Setting<SettingColor> backgroundColor = sgBackground.add(new ColorSetting.Builder()
        .name("background-color")
        .visible(background::get)
        .defaultValue(new SettingColor(25, 18, 40, 160))
        .build()
    );

    // ── State ─────────────────────────────────────────────────────────────────
    private Player playerEntity;

    public CombatHud() {
        super(INFO);
        calculateSize();
    }

    private void calculateSize() {
        setSize(180 * getScale(), 100 * getScale());
    }

    // ── Render ────────────────────────────────────────────────────────────────
    @Override
    public void render(HudRenderer renderer) {
        renderer.post(() -> {
            double x = this.x;
            double y = this.y;

            Color primaryColor   = TextHud.getSectionColor(0);
            Color secondaryColor = TextHud.getSectionColor(1);

            playerEntity = isInEditor()
                ? mc.player
                : TargetUtils.getPlayerTarget(range.get(), SortPriority.LowestDistance);

            if (playerEntity == null && !isInEditor()) return;

            // ── Panel background ──────────────────────────────────────────────
            if (background.get()) {
                Renderer2D.COLOR.begin();
                Renderer2D.COLOR.quad(x, y, getWidth(), getHeight(), backgroundColor.get());
                Renderer2D.COLOR.render();
            }

            if (playerEntity == null) {
                if (isInEditor()) {
                    renderer.line(x, y, x + getWidth(), y + getHeight(), Color.GRAY);
                    renderer.line(x + getWidth(), y, x, y + getHeight(), Color.GRAY);
                }
                return;
            }

            // ── Player model ──────────────────────────────────────────────────
            int modelSize = (int) (50 * getScale());
            renderer.entity(
                playerEntity,
                (int) (x + PAD * getScale()),
                (int) (y + PAD * getScale()),
                modelSize, modelSize,
                -Mth.wrapDegrees(playerEntity.yRotO
                    + (playerEntity.getYRot() - playerEntity.yRotO)
                    * mc.getDeltaTracker().getGameTimeDeltaPartialTick(true)),
                -playerEntity.getXRot()
            );

            // ── Text section (right of model) ─────────────────────────────────
            double tx = x + modelSize + PAD * getScale() * 1.5;
            double ty = y + PAD * getScale();

            // Resolve label & colours
            String nameText  = playerEntity.getName().getString();
            Color  nameColor = PlayerUtils.getPlayerColor(playerEntity, primaryColor);

            int    ping      = EntityUtils.getPing(playerEntity);
            Color  pingColor = ping <= 75 ? pingColor1.get() : ping <= 200 ? pingColor2.get() : pingColor3.get();

            double dist      = isInEditor() ? 0 : Math.round(mc.player.distanceTo(playerEntity) * 100.0) / 100.0;
            Color  distColor = dist <= 10  ? distColor1.get() : dist <= 50 ? distColor2.get() : distColor3.get();

            String statusText  = resolveStatus();
            Color  statusColor = resolveStatusColor(primaryColor);

            TextRenderer.get().begin(0.42 * getScale(), false, true);

            // Name row
            TextRenderer.get().render(nameText, tx, ty, nameColor != null ? nameColor : primaryColor);
            ty += TextRenderer.get().getHeight() + 1;

            // Status | ping | distance row
            double cx = tx;
            TextRenderer.get().render(statusText, cx, ty, statusColor);
            cx += TextRenderer.get().getWidth(statusText);

            if (displayPing.get()) {
                TextRenderer.get().render(" | ", cx, ty, secondaryColor);
                cx += TextRenderer.get().getWidth(" | ");
                String pingStr = ping + "ms";
                TextRenderer.get().render(pingStr, cx, ty, pingColor);
                cx += TextRenderer.get().getWidth(pingStr);
            }

            if (displayDistance.get()) {
                TextRenderer.get().render(" | ", cx, ty, secondaryColor);
                cx += TextRenderer.get().getWidth(" | ");
                TextRenderer.get().render(dist + "m", cx, ty, distColor);
            }

            TextRenderer.get().end();

            // ── Thin divider ──────────────────────────────────────────────────
            double divY = y + modelSize + PAD * getScale();
            Renderer2D.COLOR.begin();
            Renderer2D.COLOR.quad(x + PAD * getScale(), divY, getWidth() - PAD * getScale() * 2, DIVIDER_H, DIMMED);
            Renderer2D.COLOR.render();

            // ── Armor row ─────────────────────────────────────────────────────
            double armorY  = divY + DIVIDER_H + SECTION_GAP * getScale();
            double slotW   = 18 * getScale();
            double armorX  = x + PAD * getScale();

            Matrix4fStack matrices = RenderSystem.getModelViewStack();
            matrices.pushMatrix();
            matrices.scale((float) getScale(), (float) getScale(), 1);

            TextRenderer.get().begin(0.32, false, true);

            int slot = 5;
            for (int pos = 0; pos < 6; pos++) {
                ItemStack itemStack = getItem(slot);
                renderer.item(itemStack, (int) armorX, (int) armorY, (float) getScale(), true);

                double enchY = (armorY / getScale()) + 18;
                double enchX = armorX / getScale() + (slotW / getScale()) / 2.0;

                ItemEnchantments enchants = EnchantmentHelper.getEnchantmentsForCrafting(itemStack);
                List<ObjectIntPair<Holder<Enchantment>>> toShow = new ArrayList<>();

                for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchants.entrySet()) {
                    if (entry.getKey().is(displayedEnchantments.get()::contains))
                        toShow.add(new ObjectIntImmutablePair<>(entry.getKey(), entry.getIntValue()));
                }

                for (ObjectIntPair<Holder<Enchantment>> entry : toShow) {
                    String label = Utils.getEnchantSimpleName(entry.left(), 3) + " " + entry.rightInt();
                    TextRenderer.get().render(
                        label,
                        enchX - TextRenderer.get().getWidth(label) / 2.0,
                        enchY,
                        entry.left().is(EnchantmentTags.CURSE) ? RED : enchantmentTextColor.get()
                    );
                    enchY += TextRenderer.get().getHeight();
                }

                armorX += slotW + 2 * getScale();
                slot--;
            }

            TextRenderer.get().end();
            matrices.popMatrix();

            // ── Health bar ────────────────────────────────────────────────────
            double barY = this.y + getHeight() - BAR_HEIGHT * getScale() - PAD * getScale();
            double barX = this.x + PAD * getScale();
            double barW = getWidth() - PAD * getScale() * 2;

            float maxHealth = playerEntity.getMaxHealth();
            float health    = playerEntity.getHealth();
            float absorb    = playerEntity.getAbsorptionAmount();
            int   maxAbsorb = 16;
            int   maxTotal  = (int) (maxHealth + maxAbsorb);

            double healthFrac = health / maxHealth;
            double absorbFrac = Math.min(absorb / maxAbsorb, 1.0);

            double healthW = barW * (maxHealth / maxTotal) * healthFrac;
            double absorbW = barW * (maxAbsorb / (double) maxTotal) * absorbFrac;

            Renderer2D.COLOR.begin();
            // Track
            Renderer2D.COLOR.quad(barX, barY, barW, BAR_HEIGHT * getScale(), DARK);
            // Health fill – gradient from healthColor1 to healthColor3 via healthColor2
            Renderer2D.COLOR.quad(barX, barY, healthW, BAR_HEIGHT * getScale(),
                healthColor1.get(), healthColor2.get(), healthColor2.get(), healthColor1.get());
            // Absorb fill
            Renderer2D.COLOR.quad(barX + healthW, barY, absorbW, BAR_HEIGHT * getScale(),
                healthColor2.get(), healthColor3.get(), healthColor3.get(), healthColor2.get());
            Renderer2D.COLOR.render();
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private String resolveStatus() {
        if (playerEntity == null) return "Unknown";
        if (Friends.get().isFriend(playerEntity)) return "Friend";

        boolean naked = true;
        for (int i = 3; i >= 0; i--) {
            if (!getItem(i).isEmpty()) { naked = false; break; }
        }
        if (naked) return "Naked";

        for (int i = 5; i >= 0; i--) {
            ItemStack s = getItem(i);
            if (s.is(ItemTags.SWORDS)
                || s.getItem() == Items.END_CRYSTAL
                || s.getItem() == Items.RESPAWN_ANCHOR
                || s.getItem() instanceof BedItem) return "Threat";
        }

        return "Unknown";
    }

    private Color resolveStatusColor(Color fallback) {
        if (playerEntity == null) return fallback;
        if (Friends.get().isFriend(playerEntity)) return Config.get().friendColor.get();
        String status = resolveStatus();
        return switch (status) {
            case "Naked"  -> GREEN;
            case "Threat" -> RED;
            default       -> fallback;
        };
    }

    private ItemStack getItem(int i) {
        if (isInEditor()) return switch (i) {
            case 0 -> DisplayItemUtils.toStack(Items.NETHERITE_BOOTS);
            case 1 -> DisplayItemUtils.toStack(Items.NETHERITE_LEGGINGS);
            case 2 -> DisplayItemUtils.toStack(Items.NETHERITE_CHESTPLATE);
            case 3 -> DisplayItemUtils.toStack(Items.NETHERITE_HELMET);
            case 4 -> DisplayItemUtils.toStack(Items.TOTEM_OF_UNDYING);
            case 5 -> DisplayItemUtils.toStack(Items.END_CRYSTAL);
            default -> ItemStack.EMPTY;
        };

        if (playerEntity == null) return ItemStack.EMPTY;

        return switch (i) {
            case 5 -> playerEntity.getMainHandItem();
            case 4 -> playerEntity.getOffhandItem();
            case 3 -> playerEntity.getItemBySlot(EquipmentSlot.HEAD);
            case 2 -> playerEntity.getItemBySlot(EquipmentSlot.CHEST);
            case 1 -> playerEntity.getItemBySlot(EquipmentSlot.LEGS);
            case 0 -> playerEntity.getItemBySlot(EquipmentSlot.FEET);
            default -> ItemStack.EMPTY;
        };
    }

    private double getScale() {
        return customScale.get() ? scale.get() : Hud.get().getTextScale();
    }
}
