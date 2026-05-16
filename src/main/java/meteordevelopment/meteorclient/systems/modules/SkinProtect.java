/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.UUID;

/**
 * SkinProtect — randomizes your skin client-side to hide your identity.
 *
 * Three modes:
 *  - RANDOM   : picks a stable-but-fake UUID so you get a consistent random skin
 *               each session.  Nobody on the server sees a different skin; this is
 *               purely client-side and only affects what YOUR client renders for you.
 *  - STEVE    : forces the classic Steve default skin (fully offline).
 *  - REAL     : disables protection and shows your actual skin.
 *
 * Because this is 100% client-side it cannot be detected by servers.
 */
public class SkinProtect extends Module {

    // ── Setting group ─────────────────────────────────────────────────────────
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("How to protect your skin.")
        .defaultValue(Mode.Random)
        .build()
    );

    public final Setting<Boolean> affectOthers = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-others-skins")
        .description("Also randomize other players' skins client-side (good for streams/screenshots).")
        .defaultValue(false)
        .build()
    );

    // ── State ─────────────────────────────────────────────────────────────────

    /**
     * A stable random UUID generated once per session.
     * Using a fixed seed per-session means the skin stays the same while you play
     * but changes next time you launch — so it's consistent but not traceable.
     */
    private final UUID sessionUUID = UUID.randomUUID();

    public SkinProtect() {
        super(Categories.Player, "skin-protect",
            "Randomizes your client-side skin to protect your identity. 100% client-side only.");
    }

    // ── Public API (called from PlayerInfoMixin) ───────────────────────────────

    /**
     * Returns the skin that should be displayed for a given profile name.
     * Returns null if SkinProtect is inactive or mode is REAL.
     *
     * @param profileName  the username whose skin is being requested
     * @param isLocalPlayer true if this is the local player (you)
     */
    public PlayerSkin getSkin(String profileName, boolean isLocalPlayer) {
        if (!isActive()) return null;

        boolean isMe = isLocalPlayer
            || (mc.getUser() != null && mc.getUser().getName().equals(profileName));

        // Always protect the local player; only protect others if the setting is on
        if (!isMe && !affectOthers.get()) return null;

        return switch (mode.get()) {
            case Random -> DefaultPlayerSkin.get(isMe ? sessionUUID : deriveUUID(profileName));
            case Steve  -> DefaultPlayerSkin.get(new UUID(0, 0));
            case Real   -> null; // pass-through — show the real skin
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Derives a stable fake UUID from a username so the same player always
     *  gets the same random skin this session, avoiding flickering. */
    private UUID deriveUUID(String name) {
        long seed = name.hashCode() ^ sessionUUID.getMostSignificantBits();
        return new UUID(seed, sessionUUID.getLeastSignificantBits());
    }

    // ── Enum ──────────────────────────────────────────────────────────────────
    public enum Mode {
        /** Stable random skin per session — changes on restart */
        Random,
        /** Classic Steve — offline, no network calls */
        Steve,
        /** Show your real skin (protection off) */
        Real
    }
}
