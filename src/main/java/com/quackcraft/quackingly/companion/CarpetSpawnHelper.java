package com.quackcraft.quackingly.companion;

import carpet.CarpetSettings;
import carpet.patches.EntityPlayerMPFake;
import com.quackcraft.quackingly.Quackingly;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Carpet-dependent spawn logic. This class is only loaded when Carpet is present.
 *
 * IMPORTANT: Carpet lowercases the fake-player name ("Bob" → "bob")
 * and the join happens ASYNC (on the network thread). So after calling createFake,
 * we can't immediately look up the player — we need to retry with a delay.
 *
 * createFake MUST be called on the server thread (it modifies server state).
 * lookupFakePlayer can be called from any thread (it just reads the player list).
 */
public final class CarpetSpawnHelper {

    public static final String FAKE_PLAYER_NAME = "Bob";
    private static final int MAX_LOOKUP_RETRIES = 120;  // allow up to 6 seconds for Carpet's async join
    private static final long RETRY_DELAY_MS = 50;
    private static final ScheduledExecutorService RETRY_TIMER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Bob-Spawn-Timer");
        thread.setDaemon(true);
        return thread;
    });

    private CarpetSpawnHelper() {}

    /**
     * Tell Carpet to create the fake player. MUST be called on the server thread.
     * The player won't be immediately available — use lookupFakePlayer() to wait for it.
     */
    public static void createFakePlayer(MinecraftServer server, ServerPlayerEntity host) {
        CarpetSettings.allowSpawningOfflinePlayers = true;
        // Spawn at host's EXACT position (not +1 offset which could be over a hole/void)
        Vec3d pos = new Vec3d(host.getX(), host.getY(), host.getZ());
        EntityPlayerMPFake.createFake(
                FAKE_PLAYER_NAME,
                server,
                pos,
                host.getYaw(), 0f,
                host.getWorld().getRegistryKey(),
                GameMode.SURVIVAL,
                false);
    }

    /** Poll the player list on the server thread; reading it from a sleeping worker raced Carpet's join. */
    public static void lookupFakePlayerAsync(MinecraftServer server, Consumer<ServerPlayerEntity> callback) {
        AtomicInteger attempts = new AtomicInteger();
        Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            ServerPlayerEntity found = server.getPlayerManager().getPlayer(FAKE_PLAYER_NAME);
            if (found == null) {
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    if (player.getName().getString().equalsIgnoreCase(FAKE_PLAYER_NAME)) {
                        found = player;
                        break;
                    }
                }
            }
            int attempt = attempts.getAndIncrement();
            if (found != null) {
                Quackingly.LOGGER.info("[Quackingly] Bob joined successfully after {} checks.", attempt);
                callback.accept(found);
            } else if (attempt >= MAX_LOOKUP_RETRIES) {
                Quackingly.LOGGER.error("[Quackingly] Fake player '{}' not found after {} checks.", FAKE_PLAYER_NAME, attempt);
                callback.accept(null);
            } else {
                RETRY_TIMER.schedule(() -> {
                    try {
                        server.execute(poll[0]);
                    } catch (RuntimeException stoppingServer) {
                        Quackingly.LOGGER.debug("[Quackingly] Spawn lookup stopped because the world is closing.");
                    }
                }, RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        };
        server.execute(poll[0]);
    }

    @SuppressWarnings("unchecked")
    public static Class<? extends net.minecraft.entity.player.PlayerEntity> fakePlayerClass() {
        return EntityPlayerMPFake.class;
    }
}
