/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.hud.elements;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

import java.util.ArrayList;
import java.util.List;

public class ActiveModulesHud extends HudElement {
    public static final HudElementInfo<ActiveModulesHud> INFO = new HudElementInfo<>(
        Hud.GROUP, "active-modules", "Displays your active modules.", ActiveModulesHud::new
    );

    // ── Layout constants ──────────────────────────────────────────────────────
    private static final double PAD_X         = 6;   // horizontal inner padding
    private static final double PAD_Y         = 3;   // vertical inner padding
    private static final double LINE_GAP      = 2;   // gap between rows
    private static final double INDICATOR_W   = 2;   // left accent bar width
    private static final double INDICATOR_GAP = 4;   // gap: accent bar → text

    private static final Color WHITE = new Color(255, 255, 255, 255);

    // ── Setting groups ────────────────────────────────────────────────────────
    private final SettingGroup sgGeneral    = settings.getDefaultGroup();
    private final SettingGroup sgColor      = settings.createGroup("Color");
    private final SettingGroup sgBackground = settings.createGroup("Background");
    private final SettingGroup sgScale      = settings.createGroup("Scale");

    // General
    private final Setting<Sort> sort = sgGeneral.add(new EnumSetting.Builder<Sort>()
        .name("sort")
        .description("How to sort active modules.")
        .defaultValue(Sort.Biggest)
        .build()
    );

    private final Setting<List<Module>> hiddenModules = sgGeneral.add(new ModuleListSetting.Builder()
        .name("hidden-modules")
        .description("Modules not shown in the list.")
        .build()
    );

    private final Setting<Boolean> activeInfo = sgGeneral.add(new BoolSetting.Builder()
        .name("module-info")
        .description("Shows extra info from the module next to its name.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showKeybind = sgGeneral.add(new BoolSetting.Builder()
        .name("show-keybind")
        .description("Shows the module keybind next to its name.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> shadow = sgGeneral.add(new BoolSetting.Builder()
        .name("shadow")
        .description("Renders a drop shadow behind text.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> accentBar = sgGeneral.add(new BoolSetting.Builder()
        .name("accent-bar")
        .description("Renders a coloured bar on the left edge of each module row.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Alignment> alignment = sgGeneral.add(new EnumSetting.Builder<Alignment>()
        .name("alignment")
        .description("Horizontal text alignment.")
        .defaultValue(Alignment.Auto)
        .build()
    );

    // Color
    private final Setting<ColorMode> colorMode = sgColor.add(new EnumSetting.Builder<ColorMode>()
        .name("color-mode")
        .description("Color mode for module names.")
        .defaultValue(ColorMode.Flat)
        .build()
    );

    private final Setting<SettingColor> flatColor = sgColor.add(new ColorSetting.Builder()
        .name("flat-color")
        .description("Color used in flat mode.")
        .defaultValue(new SettingColor(179, 127, 255))
        .visible(() -> colorMode.get() == ColorMode.Flat)
        .build()
    );

    private final Setting<Double> rainbowSpeed = sgColor.add(new DoubleSetting.Builder()
        .name("rainbow-speed")
        .defaultValue(0.05)
        .sliderMin(0.01).sliderMax(0.2)
        .decimalPlaces(4)
        .visible(() -> colorMode.get() == ColorMode.Rainbow)
        .build()
    );

    private final Setting<Double> rainbowSpread = sgColor.add(new DoubleSetting.Builder()
        .name("rainbow-spread")
        .defaultValue(0.01)
        .sliderMin(0.001).sliderMax(0.05)
        .decimalPlaces(4)
        .visible(() -> colorMode.get() == ColorMode.Rainbow)
        .build()
    );

    private final Setting<Double> rainbowSaturation = sgColor.add(new DoubleSetting.Builder()
        .name("rainbow-saturation")
        .defaultValue(0.75d)
        .sliderRange(0.0d, 1.0d)
        .visible(() -> colorMode.get() == ColorMode.Rainbow)
        .build()
    );

    private final Setting<Double> rainbowBrightness = sgColor.add(new DoubleSetting.Builder()
        .name("rainbow-brightness")
        .defaultValue(1.0d)
        .sliderRange(0.0d, 1.0d)
        .visible(() -> colorMode.get() == ColorMode.Rainbow)
        .build()
    );

    private final Setting<SettingColor> infoColor = sgColor.add(new ColorSetting.Builder()
        .name("info-color")
        .description("Color for module info / keybind text.")
        .defaultValue(new SettingColor(200, 170, 255, 200))
        .visible(activeInfo::get)
        .build()
    );

    // Background
    private final Setting<Boolean> background = sgBackground.add(new BoolSetting.Builder()
        .name("background")
        .description("Draws a background behind each module row.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgBackground.add(new ColorSetting.Builder()
        .name("background-color")
        .defaultValue(new SettingColor(25, 18, 40, 160))
        .visible(background::get)
        .build()
    );

    private final Setting<Boolean> evenOdd = sgBackground.add(new BoolSetting.Builder()
        .name("alternating-rows")
        .description("Slightly tints every other row for readability.")
        .defaultValue(false)
        .visible(background::get)
        .build()
    );

    private final Setting<SettingColor> alternateColor = sgBackground.add(new ColorSetting.Builder()
        .name("alternate-row-color")
        .defaultValue(new SettingColor(35, 25, 55, 160))
        .visible(() -> background.get() && evenOdd.get())
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

    // ── State ─────────────────────────────────────────────────────────────────
    private final List<Module> modules = new ArrayList<>();
    private final Color rainbow = new Color(255, 255, 255);
    private double rainbowHue1;
    private double rainbowHue2;

    public ActiveModulesHud() {
        super(INFO);
    }

    // ── Tick ──────────────────────────────────────────────────────────────────
    @Override
    public void tick(HudRenderer renderer) {
        modules.clear();

        for (Module module : Modules.get().getActive()) {
            if (!hiddenModules.get().contains(module)) modules.add(module);
        }

        if (modules.isEmpty()) {
            if (isInEditor()) {
                double textH = renderer.textHeight(shadow.get(), getScale());
                setSize(
                    renderer.textWidth("Active Modules", shadow.get(), getScale()) + PAD_X * 2,
                    textH + PAD_Y * 2
                );
            }
            return;
        }

        modules.sort((a, b) -> switch (sort.get()) {
            case Alphabetical -> a.title.compareTo(b.title);
            case Biggest      -> Double.compare(rowWidth(renderer, b), rowWidth(renderer, a));
            case Smallest     -> Double.compare(rowWidth(renderer, a), rowWidth(renderer, b));
        });

        double maxWidth   = 0;
        double totalHeight = PAD_Y;
        double rowH        = renderer.textHeight(shadow.get(), getScale());

        for (Module module : modules) {
            maxWidth = Math.max(maxWidth, rowWidth(renderer, module));
            totalHeight += rowH + LINE_GAP;
        }
        totalHeight += PAD_Y - LINE_GAP;

        double contentWidth = PAD_X
            + (accentBar.get() ? INDICATOR_W + INDICATOR_GAP : 0)
            + maxWidth
            + PAD_X;

        setSize(contentWidth, totalHeight);
    }

    // ── Render ────────────────────────────────────────────────────────────────
    @Override
    public void render(HudRenderer renderer) {
        double x = this.x;
        double y = this.y;

        if (modules.isEmpty()) {
            if (isInEditor()) {
                renderer.text("Active Modules", x + PAD_X, y + PAD_Y, WHITE, shadow.get(), getScale());
            }
            return;
        }

        rainbowHue1 += rainbowSpeed.get() * renderer.delta;
        if      (rainbowHue1 >  1) rainbowHue1 -= 1;
        else if (rainbowHue1 < -1) rainbowHue1 += 1;
        rainbowHue2 = rainbowHue1;

        double rowH = renderer.textHeight(shadow.get(), getScale());
        double rowY = y + PAD_Y;

        for (int i = 0; i < modules.size(); i++) {
            Module module = modules.get(i);
            Color  color  = resolveColor(module);

            double textX  = x + PAD_X + (accentBar.get() ? INDICATOR_W + INDICATOR_GAP : 0);
            double offset = alignX(rowWidth(renderer, module), alignment.get());

            // Row background
            if (background.get()) {
                Color bg = (evenOdd.get() && i % 2 == 1) ? alternateColor.get() : backgroundColor.get();
                renderer.quad(x, rowY, getWidth(), rowH + LINE_GAP, bg);
            }

            // Left accent bar
            if (accentBar.get()) {
                renderer.quad(x + PAD_X / 2.0, rowY + 1, INDICATOR_W, rowH - 2, color);
            }

            // Module name
            renderer.text(module.title, textX + offset, rowY, color, shadow.get(), getScale());
            double textLen = renderer.textWidth(module.title, shadow.get(), getScale());

            // Keybind
            if (showKeybind.get() && module.keybind.isSet()) {
                String kb = " [" + module.keybind + "]";
                renderer.text(kb, textX + offset + textLen, rowY, infoColor.get(), shadow.get(), getScale());
                textLen += renderer.textWidth(kb, shadow.get(), getScale());
            }

            // Extra info
            if (activeInfo.get()) {
                String info = module.getInfoString();
                if (info != null) {
                    double sp = renderer.textWidth(" ", shadow.get(), getScale());
                    renderer.text(info, textX + offset + textLen + sp, rowY, infoColor.get(), shadow.get(), getScale());
                }
            }

            rowY += rowH + LINE_GAP;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private Color resolveColor(Module module) {
        return switch (colorMode.get()) {
            case Flat   -> flatColor.get();
            case Random -> module.color;
            case Rainbow -> {
                rainbowHue2 += rainbowSpread.get();
                int c = java.awt.Color.HSBtoRGB(
                    (float) rainbowHue2,
                    rainbowSaturation.get().floatValue(),
                    rainbowBrightness.get().floatValue()
                );
                rainbow.r = Color.toRGBAR(c);
                rainbow.g = Color.toRGBAG(c);
                rainbow.b = Color.toRGBAB(c);
                yield rainbow;
            }
        };
    }

    private double rowWidth(HudRenderer renderer, Module module) {
        double w = renderer.textWidth(module.title, shadow.get(), getScale());

        if (showKeybind.get() && module.keybind.isSet()) {
            w += renderer.textWidth(" [" + module.keybind + "]", shadow.get(), getScale());
        }

        if (activeInfo.get()) {
            String info = module.getInfoString();
            if (info != null) {
                w += renderer.textWidth(" ", shadow.get(), getScale())
                   + renderer.textWidth(info, shadow.get(), getScale());
            }
        }

        return w;
    }

    private double getScale() {
        return customScale.get() ? scale.get() : Hud.get().getTextScale();
    }

    // ── Enums ─────────────────────────────────────────────────────────────────
    public enum Sort      { Alphabetical, Biggest, Smallest }
    public enum ColorMode { Flat, Random, Rainbow }
}
