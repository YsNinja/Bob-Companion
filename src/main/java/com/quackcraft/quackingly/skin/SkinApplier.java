package com.quackcraft.quackingly.skin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.quackcraft.quackingly.Quackingly;
import com.quackcraft.quackingly.config.QuackinglyConfig;
import net.minecraft.entity.player.PlayerEntity;

import com.quackcraft.quackingly.skin.SkinLoader.FetchedSkin;

/**
 * Applies a Mojang-fetched skin to a (fake) player.
 *
 * The default "Quack" skin is fetched ASYNC on first spawn (cached after that),
 * so it doesn't block the server thread or cause spawn lag. On subsequent spawns,
 * the cached skin is applied instantly.
 *
 * For /quack skin set <username>: fetches from Mojang live (async), applies when ready.
 */
public final class SkinApplier {

    private static volatile FetchedSkin defaultSkinCache;
    private static volatile boolean defaultSkinFetchInProgress = false;

    private SkinApplier() {}

    /** Apply the default skin (from config.defaultSkinUser, default "Quack"). Async on first call. */
    public static void applyDefaultSkin(PlayerEntity entity) {
        if (entity == null) return;
        String user = QuackinglyConfig.get().defaultSkinUser;
        if (user == null || user.isBlank()) user = "Quack";

        // Fast path: cached skin available — apply immediately
        FetchedSkin cached = defaultSkinCache;
        if (cached != null) {
            applyFetched(entity, cached);
            return;
        }

        // Slow path: no cache yet — kick off async fetch, apply when done
        if (!defaultSkinFetchInProgress) {
            defaultSkinFetchInProgress = true;
            final String username = user;
            new Thread(() -> {
                try {
                    FetchedSkin skin = SkinLoader.fetch(username);
                    if (skin != null) {
                        defaultSkinCache = skin;
                        Quackingly.LOGGER.info("[Quackingly] Default skin '{}' cached.", username);
                    }
                } catch (Exception e) {
                    Quackingly.LOGGER.warn("[Quackingly] Could not fetch default skin '{}': {}", username, e.getMessage());
                } finally {
                    defaultSkinFetchInProgress = false;
                }
            }, "Quackingly-SkinFetch").start();
        }
        // Skin will be applied on next spawn (or when /quack skin reset is called)
    }

    /** Apply a skin by fetching the given username from Mojang (async). */
    public static void applySkinFromUsername(PlayerEntity entity, String username) {
        if (entity == null) return;
        new Thread(() -> {
            try {
                FetchedSkin skin = SkinLoader.fetch(username);
                if (skin != null) {
                    // Apply on the server thread (GameProfile modification must be sync)
                    if (entity.getServer() != null) {
                        entity.getServer().execute(() -> applyFetched(entity, skin));
                    } else {
                        applyFetched(entity, skin);
                    }
                }
            } catch (Exception e) {
                Quackingly.LOGGER.warn("[Quackingly] Could not fetch skin for '{}': {}", username, e.getMessage());
            }
        }, "Quackingly-SkinFetch-" + username).start();
    }

    /** Apply an already-fetched skin's GameProfile properties to the (fake) player. */
    public static void applyFetched(PlayerEntity entity, FetchedSkin skin) {
        if (entity == null || skin == null) return;
        try {
            GameProfile profile = entity.getGameProfile();
            if (profile == null) {
                Quackingly.LOGGER.warn("[Quackingly] Cannot apply skin — player has no GameProfile.");
                return;
            }
            profile.getProperties().removeAll("textures");
            profile.getProperties().put("textures", new Property(
                    "textures", skin.textureBase64, skin.signatureBase64));
            Quackingly.LOGGER.info("[Quackingly] Applied skin '{}' to player.", skin.username);
        } catch (Throwable t) {
            Quackingly.LOGGER.error("[Quackingly] Failed to apply skin to player", t);
        }
    }
}
