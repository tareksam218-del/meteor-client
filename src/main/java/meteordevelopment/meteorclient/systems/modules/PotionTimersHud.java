/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.hud.elements;

import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.*;
import meteordevelopment.meteorclient.utils.misc.Names;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class PotionTimersHud extends HudElement {
    public static final HudElementInfo<PotionTimersHud> INFO = new HudElementInfo<>(
        Hud.GROUP, "potion-timers", "Displays active potion effects with timers.", PotionTimersHud::new
    );

    // ── Layout constants ──────────────────────────────────────────────────────
    private static final double PAD_X      = 6;
    private static final double PAD_Y      = 3;
    private static final double LINE_GAP   = 2;
    private static final double DOT_SIZE   = 4;   // coloured dot before each entry
    private static final double DOT_GAP    = 4;

    // ── Setting groups ────────────────────────────────────────────────────────
    private final SettingGroup sgGeneral    = settings.getDefaultGroup();
    private final SettingGroup sgScale      = settings.createGroup("Scale");
    private final SettingGroup sgBackground = settings.createGroup("Background");

    // General
    private final Setting<List<MobEffect>> hiddenEffects = sgGeneral.add(new StatusEffectListSetting.Builder()
        .name("hidden-effects")
        .description("Effects not shown in the list.")
        .build()
    );

    private final Setting<Boolean> showAmbient = sgGeneral.add(new BoolSetting.Builder()
        .name("show-ambient")
        .description("Show ambient effects from beacons and conduits.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ColorMode> colorMode = sgGeneral.add(new EnumSetting.Builder<ColorMode>()
        .name("color-mode")
        .description("Color mode for effect entries.")
        .defaultValue(ColorMode.Effect)
        .build()
    );

    private final Setting<SettingColor> flatColor = sgGeneral.add(new ColorSetting.Builder()
        .name("flat-color")
        .defaultValue(new SettingColor(179, 127, 255))
        .visible(() -> colorMode.get() == ColorMode.Flat)
        .build()
    );

    private final Setting<Double> rainbowSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("rainbow-speed")
        .defaultValue(0.05)
        .sliderMin(0.01).sliderMax(0.2)
        .decimalPlaces(4)
        .visible(() -> colorMode.get() == ColorMode.Rainbow)
        .build()
    );

    private final Setting<Double> rainbowSpread = sgGeneral.add(new DoubleSetting.Builder()
        .name("rainbow-spread")
        .defaultValue(0.01)
        .sliderMin(0.001).sliderMax(0.05)
        .decimalPlaces(4)
        .visible(() -> colorMode.get() == ColorMode.Rainbow)
        .build()
    );

    private final Setting<Double> rainbowSaturation = sgGeneral.add(new DoubleSetting.Builder()
        .name("rainbow-saturation")
        .defaultValue(0.75d)
        .sliderRange(0.0d, 1.0d)
        .visible(() -> colorMode.get() == ColorMode.Rainbow)
        .build()
    );

    private final Setting<Double> rainbowBrightness = sgGeneral.add(new DoubleSetting.Builder()
        .name("rainbow-brightness")
        .defaultValue(1.0d)
        .sliderRange(0.0d, 1.0d)
        .visible(() -> colorMode.get() == ColorMode.Rainbow)
        .build()
    );

    private final Setting<Boolean> showDot = sgGeneral.add(new BoolSetting.Builder()
        .name("color-dot")
        .description("Shows a small coloured dot before each effect name.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> shadow = sgGeneral.add(new BoolSetting.Builder()
        .name("shadow")
        .description("Renders a drop shadow behind text.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Alignment> alignment = sgGeneral.add(new EnumSetting.Builder<Alignment>()
        .name("alignment")
        .description("Horizontal alignment.")
        .defaultValue(Alignment.Auto)
        .build()
    );

    // Scale
    private final Setting<Boolean> customScale = sgScale.add(new BoolSetting.Builder()
        .name("custom-scale")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> scale = sgScale.add(new DoubleSetting.Builder()
        .name("scale")
        .visible(customScale::get)
        .defaultValue(1)
        .min(0.5).sliderRange(0.5, 3)
        .build()
    );

    // Background
    private final Setting<Boolean> background = sgBackground.add(new BoolSetting.Builder()
        .name("background")
        .description("Draws a background panel behind the list.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgBackground.add(new ColorSetting.Builder()
        .name("background-color")
        .visible(background::get)
        .defaultValue(new SettingColor(25, 18, 40, 160))
        .build()
    );

    // ── State ─────────────────────────────────────────────────────────────────
    private final List<Pair<MobEffectInstance, String>> entries = new ArrayList<>();
    private double rainbowHue;

    public PotionTimersHud() {
        super(INFO);
    }

    // ── Tick ──────────────────────────────────────────────────────────────────
    @Override
    public void tick(HudRenderer renderer) {
        if (mc.player == null || (isInEditor() && hasNoVisibleEffects())) {
            double textH = renderer.textHeight(shadow.get(), getScale());
            setSize(
                renderer.textWidth("Potion Timers 0:00", shadow.get(), getScale()) + PAD_X * 2,
                textH + PAD_Y * 2
            );
            return;
        }

        entries.clear();

        double maxW = 0;
        double totalH = PAD_Y;
        double rowH = renderer.textHeight(shadow.get(), getScale());

        for (MobEffectInstance fx : mc.player.getActiveEffects()) {
            if (hiddenEffects.get().contains(fx.getEffect().value())) continue;
            if (!showAmbient.get() && fx.isAmbient()) continue;

            String text = formatEffect(fx);
            entries.add(new ObjectObjectImmutablePair<>(fx, text));

            double rowW = (showDot.get() ? DOT_SIZE + DOT_GAP : 0)
                + renderer.textWidth(text, shadow.get(), getScale());
            maxW = Math.max(maxW, rowW);
            totalH += rowH + LINE_GAP;
        }

        totalH += PAD_Y - LINE_GAP;
        setSize(PAD_X + maxW + PAD_X, Math.max(totalH, PAD_Y * 2 + rowH));
    }

    // ── Render ────────────────────────────────────────────────────────────────
    @Override
    public void render(HudRenderer renderer) {
        double x = this.x;
        double y = this.y;

        if (background.get()) {
            renderer.quad(x, y, getWidth(), getHeight(), backgroundColor.get());
        }

        if (mc.player == null || (isInEditor() && hasNoVisibleEffects())) {
            renderer.text("Potion Timers 0:00", x + PAD_X, y + PAD_Y, Color.WHITE, shadow.get(), getScale());
            return;
        }

        rainbowHue += rainbowSpeed.get() * renderer.delta;
        if      (rainbowHue >  1) rainbowHue -= 1;
        else if (rainbowHue < -1) rainbowHue += 1;

        double localHue = rainbowHue;
        double rowH     = renderer.textHeight(shadow.get(), getScale());
        double rowY     = y + PAD_Y;

        for (Pair<MobEffectInstance, String> entry : entries) {
            Color color = resolveColor(entry.left(), localHue);
            localHue += rainbowSpread.get();

            double textW  = renderer.textWidth(entry.right(), shadow.get(), getScale());
            double dotOff = showDot.get() ? DOT_SIZE + DOT_GAP : 0;
            double offset = alignX(dotOff + textW, alignment.get());

            // Coloured dot
            if (showDot.get()) {
                double dotY = rowY + (rowH - DOT_SIZE) / 2.0;
                renderer.quad(x + PAD_X + offset, dotY, DOT_SIZE, DOT_SIZE, color);
            }

            // Effect text
            renderer.text(
                entry.right(),
                x + PAD_X + offset + dotOff, rowY,
                color, shadow.get(), getScale()
            );

            rowY += rowH + LINE_GAP;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private Color resolveColor(MobEffectInstance fx, double hue) {
        return switch (colorMode.get()) {
            case Effect -> new Color(fx.getEffect().value().getColor()).a(255);
            case Flat   -> { flatColor.get().update(); yield flatColor.get(); }
            case Rainbow -> {
                int c = java.awt.Color.HSBtoRGB(
                    (float) hue,
                    rainbowSaturation.get().floatValue(),
                    rainbowBrightness.get().floatValue()
                );
                yield new Color(c);
            }
        };
    }

    private String formatEffect(MobEffectInstance fx) {
        return String.format("%s %d (%s)",
            Names.get(fx.getEffect().value()),
            fx.getAmplifier() + 1,
            MobEffectUtil.formatDuration(fx, 1, mc.level.tickRateManager().tickrate()).getString()
        );
    }

    private double getScale() {
        return customScale.get() ? scale.get() : Hud.get().getTextScale();
    }

    private boolean hasNoVisibleEffects() {
        for (MobEffectInstance fx : mc.player.getActiveEffects()) {
            if (hiddenEffects.get().contains(fx.getEffect().value())) continue;
            if (!showAmbient.get() && fx.isAmbient()) continue;
            return false;
        }
        return true;
    }

    public enum ColorMode { Effect, Flat, Rainbow }
}
