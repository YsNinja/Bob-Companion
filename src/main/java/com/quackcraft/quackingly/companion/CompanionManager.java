package com.quackcraft.quackingly.companion;

import com.quackcraft.quackingly.Quackingly;
import com.quackcraft.quackingly.config.QuackinglyConfig;
import com.quackcraft.quackingly.llm.ConversationMemory;
import com.quackcraft.quackingly.llm.KeyRotation;
import com.quackcraft.quackingly.llm.LLMProvider;
import com.quackcraft.quackingly.llm.PromptManager;
import com.quackcraft.quackingly.network.ServerCompanionPackets;
import com.quackcraft.quackingly.skin.SkinApplier;
import com.quackcraft.quackingly.voice.SilenceWatcher;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.world.RaycastContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-server manager for the Quackingly companion.
 *
 * Uses Carpet's EntityPlayerMPFake (when Carpet is installed) to spawn a real
 * fake-player that looks/acts like a normal player. One Quackingly per world
 * (per online player host).
 *
 * Carpet is optional — if absent, summon fails gracefully with a friendly message.
 *
 * Token optimisation:
 *   - We only call the LLM when there's a new user message (event-driven, not tick-polling).
 *   - Each player's conversation uses a ConversationMemory that auto-compacts.
 *   - Per-call context is bounded (~2k tokens) regardless of session length.
 */
public class CompanionManager {
    private static final CompanionManager INSTANCE = new CompanionManager();
    private static final boolean CARPET_PRESENT =
            FabricLoader.getInstance().isModLoaded("carpet");

    private MinecraftServer server;
    private final ConcurrentHashMap<UUID, CompanionSession> sessions = new ConcurrentHashMap<>();

    public static CompanionManager getInstance() { return INSTANCE; }
    public static boolean isCarpetPresent() { return CARPET_PRESENT; }

    public void onServerStarted(MinecraftServer server) {
        this.server = server;
        Quackingly.LOGGER.info("[Quackingly] Server started. Carpet present: {}", CARPET_PRESENT);
    }

    public void onServerStopped() {
        for (CompanionSession s : sessions.values()) s.despawn();
        sessions.clear();
        server = null;
    }

    public MinecraftServer getServer() { return server; }

    /** Toggle summon/despawn for the given player's Quackingly. */
    public boolean toggle(ServerPlayerEntity host) {
        CompanionSession s = sessions.get(host.getUuid());
        if (s != null && s.isAlive()) {
            s.despawn();
            sessions.remove(host.getUuid());
            SilenceWatcher.onSessionEnd(host.getUuid());
            host.sendMessage(Text.translatable("chat.quackingly.despawned").formatted(Formatting.YELLOW));
            return false;
        }
        if (s != null && s.isSpawning()) {
            host.sendMessage(Text.literal("Bob is still joining. Give him a moment.").formatted(Formatting.GRAY));
            return true;
        }
        if (!CARPET_PRESENT) {
            host.sendMessage(Text.literal("Quackingly needs the Carpet mod to spawn. " +
                    "Install Carpet (1.21.1) from Modrinth and try again.").formatted(Formatting.RED));
            return false;
        }
        CompanionSession ns = new CompanionSession(host);
        sessions.put(host.getUuid(), ns);
        host.sendMessage(Text.literal("Spawning Quackingly...").formatted(Formatting.GRAY));
        ns.spawnAsync(ok -> {
            if (ok) {
                host.sendMessage(Text.translatable("chat.quackingly.summoned").formatted(Formatting.AQUA));
                SilenceWatcher.onSessionStart(host.getUuid());
                // Warn if voice input won't work (Opus broken on this platform)
                try {
                    if (!com.quackcraft.quackingly.voice.QuackinglyVoiceChatPlugin.isOpusAvailable()) {
                        host.sendMessage(Text.literal("⚠ SVC voice input unavailable. Using built-in mic capture or text chat.").formatted(Formatting.YELLOW));
                    }
                } catch (NoClassDefFoundError svc404) {
                    // SVC not installed — that's fine, Java Sound fallback or text chat works
                }
            } else {
                sessions.remove(host.getUuid());
                host.sendMessage(Text.literal("Quackingly could not spawn. Check the log.").formatted(Formatting.RED));
            }
        });
        return true;
    }

    /** Send a chat message TO Quackingly on behalf of a player. */
    public void sendToCompanion(ServerPlayerEntity host, String message) {
        CompanionSession s = sessions.get(host.getUuid());
        if (s == null || !s.isAlive()) {
            host.sendMessage(Text.literal("Summon Bob first (press K or type /quackingly).").formatted(Formatting.RED));
            return;
        }
        s.handleUserMessage(host, message);
    }

    /**
     * Summon Quackingly with a specific mode (called from the /quackingly
     * confirmation flow). If already summoned, despawn first then respawn
     * with the new mode.
     */
    public boolean summonWithMode(ServerPlayerEntity host, String mode) {
        // Despawn existing session if any
        CompanionSession existing = sessions.get(host.getUuid());
        if (existing != null && existing.isSpawning()) {
            host.sendMessage(Text.literal("Bob is still joining. Give him a moment.").formatted(Formatting.GRAY));
            return true;
        }
        if (existing != null && existing.isAlive()) {
            existing.despawn();
            sessions.remove(host.getUuid());
            SilenceWatcher.onSessionEnd(host.getUuid());
        }

        if (!CARPET_PRESENT) {
            host.sendMessage(Text.literal("Quackingly needs the Carpet mod to spawn. " +
                    "Install Carpet (1.21.1) from Modrinth and try again.").formatted(Formatting.RED));
            return false;
        }

        CompanionSession ns = new CompanionSession(host);
        ns.setMode(mode);
        sessions.put(host.getUuid(), ns);
        host.sendMessage(Text.literal("Spawning Quackingly...").formatted(Formatting.GRAY));
        ns.spawnAsync(ok -> {
            if (ok) {
                host.sendMessage(Text.translatable("chat.quackingly.summoned").formatted(Formatting.AQUA));
                host.sendMessage(Text.literal("Mode: " + mode).formatted(Formatting.YELLOW));
                SilenceWatcher.onSessionStart(host.getUuid());
            } else {
                sessions.remove(host.getUuid());
                host.sendMessage(Text.literal("Quackingly could not spawn. Check the log.").formatted(Formatting.RED));
            }
        });
        return true;
    }

    public CompanionSession getSession(ServerPlayerEntity host) {
        return sessions.get(host.getUuid());
    }

    /** Read-only view of all active sessions (used by CompanionBrain for tick). */
    public java.util.Collection<CompanionSession> sessionsView() {
        return sessions.values();
    }

    /* ---- Per-player session ---- */

    public class CompanionSession {
        private final ServerPlayerEntity host;
        private volatile PlayerEntity fakePlayer; // EntityPlayerMPFake at runtime (when Carpet is present)
        private final ConversationMemory memory = new ConversationMemory();
        private KeyRotation llmKeyRotation;     // rebuilt when config changes
        private String mode;
        private volatile boolean spawning;

        public CompanionSession(ServerPlayerEntity host) {
            this.host = host;
            this.mode = QuackinglyConfig.get().defaultMode;
            rebuildKeyRotation();
        }

        /** Rebuild the key rotation from current config. Call when config changes. */
        public void rebuildKeyRotation() {
            QuackinglyConfig.ConfigData cfg = QuackinglyConfig.get();
            llmKeyRotation = new KeyRotation(cfg.apiKey, cfg.backupApiKeys);
        }

        public ServerPlayerEntity getHost() { return host; }

        /**
         * Spawn the fake player. createFake is called synchronously on the server thread
         * (required by Carpet), then the lookup happens on a worker thread (with retries
         * because Carpet's player join is async). The caller's success/failure message
         * is sent after the lookup completes.
         *
         * @param onComplete called on the server thread after spawn succeeds (true) or fails (false)
         */
        public void spawnAsync(java.util.function.Consumer<Boolean> onComplete) {
            if (server == null) { onComplete.accept(false); return; }
            try {
                // Step 1: createFake MUST be on the server thread (we already are)
                CarpetSpawnHelper.createFakePlayer(server, host);
                spawning = true;
                CarpetSpawnHelper.lookupFakePlayerAsync(server, found -> {
                    spawning = false;
                    if (found == null) {
                        onComplete.accept(false);
                        return;
                    }
                    fakePlayer = found;
                    SkinApplier.applyDefaultSkin(fakePlayer);
                    onComplete.accept(true);
                });
            } catch (Throwable t) {
                spawning = false;
                Quackingly.LOGGER.error("Failed to spawn Quackingly", t);
                onComplete.accept(false);
            }
        }

        public void despawn() {
            if (fakePlayer != null) {
                try { fakePlayer.kill(); } catch (Throwable ignored) {}
                fakePlayer = null;
            }
        }

        public boolean isAlive() { return fakePlayer != null && !fakePlayer.isRemoved(); }

        public boolean isSpawning() { return spawning; }

        public PlayerEntity getFakePlayer() { return fakePlayer; }

        public void setMode(String m) { this.mode = m; }
        public String getMode() { return mode; }
        private Boolean followOverride;
        private long lastDefenseAttackTick = -1000;
        private long lastThreatWarningTick = -1000;

        public boolean isFollowing() {
            return followOverride != null ? followOverride : QuackinglyConfig.get().followPlayer;
        }

        public void setFollowOverride(boolean value) { followOverride = value; }
        public boolean canAttackForDefense(long currentTick) { return currentTick - lastDefenseAttackTick >= 4; }
        public void markDefenseAttack(long currentTick) { lastDefenseAttackTick = currentTick; }
        public boolean canWarnAboutThreat(long currentTick) { return currentTick - lastThreatWarningTick >= 600; }
        public void markThreatWarning(long currentTick) { lastThreatWarningTick = currentTick; }

        public void handleUserMessage(ServerPlayerEntity from, String text) {
            memory.addUser("[" + from.getName().getString() + "] " + text);
            rebuildKeyRotation();
            host.sendMessage(Text.literal("Bob is thinking...").formatted(Formatting.ITALIC, Formatting.GRAY));
            // Snapshot all world state on the server thread before the network request.
            final String worldContext = describeWorld(from);

            new Thread(() -> {
                try {
                    String currentKey = llmKeyRotation.current();
                    if (currentKey.isBlank()) {
                        server.execute(() -> host.sendMessage(
                                Text.literal("§c[Bob] No API key set. Open Mods → Bob Companion, or press B for settings.")
                                        .formatted(Formatting.RED)));
                        return;
                    }

                    String reply = null;
                    Exception lastError = null;

                    while (reply == null && currentKey != null && !currentKey.isBlank()) {
                        try {
                            LLMProvider llm = LLMProvider.fromConfig(currentKey);
                            String prompt = PromptManager.systemPrompt(mode, from.getName().getString(), worldContext);
                            reply = llm.chat(memory.buildForCall(prompt));
                        } catch (LLMProvider.LLMHttpException e) {
                            Quackingly.LOGGER.error("[Quackingly] LLM HTTP {}: {}", e.httpStatus, e.getMessage());
                            if (LLMProvider.isKeyOrQuotaError(e.httpStatus) && llmKeyRotation.hasMore()) {
                                currentKey = llmKeyRotation.advance();
                                continue;
                            }
                            lastError = e;
                            break;
                        } catch (Exception e) {
                            Quackingly.LOGGER.error("[Quackingly] LLM call error", e);
                            lastError = e;
                            break;
                        }
                    }

                    if (reply == null) {
                        final Exception err = lastError;
                        String msg;
                        if (err instanceof LLMProvider.LLMHttpException) {
                            int status = ((LLMProvider.LLMHttpException) err).httpStatus;
                            msg = friendlyLlmError(status);
                        } else if (err != null) {
                            msg = err.getMessage();
                        } else {
                            msg = "No LLM keys available.";
                        }
                        Quackingly.LOGGER.error("[Quackingly] LLM failed, showing to host: {}", msg);
                        final String errMsg = msg;
                        server.execute(() -> host.sendMessage(
                                Text.literal("§c[Quackingly] " + errMsg).formatted(Formatting.RED)));
                        return;
                    }

                    BobActionPlan plan = BobActionPlan.parse(reply);
                    String spokenReply = plan.speech().isBlank() ? "I'll do my best." : plan.speech();
                    memory.addAssistant(spokenReply);
                    String responseMode = QuackinglyConfig.get().responseMode;
                    if (responseMode == null || responseMode.isBlank()) responseMode = "both";

                    final boolean showTextReply = responseMode.equals("chat_only") || responseMode.equals("both");
                    final String replyText = spokenReply;
                    server.execute(() -> {
                        if (host.isRemoved()) return;
                        if (showTextReply) host.sendMessage(Text.literal("<Bob> " + replyText).formatted(Formatting.WHITE));
                        if (fakePlayer instanceof ServerPlayerEntity bob && !bob.isRemoved()) {
                            BobActionExecutor.execute(host, bob, plan, text, this::setFollowOverride);
                        }
                    });
                    if (responseMode.equals("voice_only") || responseMode.equals("both")) {
                        ServerCompanionPackets.sendTtsReply(from, spokenReply);
                    }
                } catch (Exception e) {
                    Quackingly.LOGGER.error("[Quackingly] LLM call failed", e);
                    server.execute(() -> host.sendMessage(
                            Text.literal("§c[Quackingly] Error: " + e.getMessage()).formatted(Formatting.RED)));
                }
            }, "Quackingly-LLM").start();
        }

        private static String friendlyLlmError(int status) {
            switch (status) {
                case 401: return "API key is invalid. Check your configured provider's key in Bob settings.";
                case 402: return "API quota exhausted. You need to add credits or wait.";
                case 403: return "API key is forbidden or revoked. Check your configured provider's key.";
                case 404: return "Model not found. Check the model name in Mods → Bob Companion → Model.";
                case 429: return "Rate limited — too many requests. Wait a moment and try again.";
                case 500: case 502: case 503: return "LLM server error. Try again in a moment.";
                default:  return "LLM error (HTTP " + status + "). Check console for details.";
            }
        }

        private String describeWorld(ServerPlayerEntity p) {
            try {
                // Richer world context — helps the LLM respond like a real player would
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("Player %s at (%.0f, %.0f, %.0f) in %s, %s, time %d ticks",
                        p.getName().getString(),
                        p.getX(), p.getY(), p.getZ(),
                        p.getWorld().getRegistryKey().getValue(),
                        p.getWorld().isNight() ? "night" : "day",
                        p.getWorld().getTimeOfDay() % 24000));
                sb.append(String.format("; health %.0f/%.0f, hunger %d/20, air %d",
                        p.getHealth(), p.getMaxHealth(), p.getHungerManager().getFoodLevel(), p.getAir()));
                if (p.isOnFire()) sb.append("; on fire");
                if (p.isInLava()) sb.append("; in lava");
                if (p.isTouchingWater()) sb.append("; in water");
                if (p.isSubmergedInWater()) sb.append("; underwater");
                if (p.isSneaking()) sb.append("; sneaking");
                if (p.isSprinting()) sb.append("; sprinting");
                if (p.isSleeping()) sb.append("; player is in bed");

                var lookStart = p.getEyePos();
                var lookEnd = lookStart.add(p.getRotationVec(1.0f).multiply(6));
                var lookingAt = p.getWorld().raycast(new RaycastContext(lookStart, lookEnd,
                        RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, p));
                if (lookingAt.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
                    BlockPos targetPos = lookingAt.getBlockPos();
                    sb.append("; player is looking at ").append(Registries.BLOCK.getId(p.getWorld().getBlockState(targetPos).getBlock()))
                            .append(" at ").append(targetPos.getX()).append(',').append(targetPos.getY()).append(',').append(targetPos.getZ());
                }

                var nearby = p.getServerWorld().getOtherEntities(p, p.getBoundingBox().expand(12),
                        entity -> entity.isAlive());
                int hostileCount = 0;
                int villagerCount = 0;
                for (Entity entity : nearby) {
                    if (entity instanceof HostileEntity hostile && hostileCount < 6) {
                        sb.append(String.format("; hostile[id=%d,type=%s,pos=%d,%d,%d,distance=%.1f]",
                                hostile.getId(), Registries.ENTITY_TYPE.getId(hostile.getType()),
                                hostile.getBlockX(), hostile.getBlockY(), hostile.getBlockZ(), hostile.distanceTo(p)));
                        hostileCount++;
                    } else if (entity instanceof VillagerEntity villager && villagerCount < 3) {
                        sb.append(String.format("; villager[id=%d,pos=%d,%d,%d,trades=",
                                villager.getId(), villager.getBlockX(), villager.getBlockY(), villager.getBlockZ()));
                        int count = Math.min(4, villager.getOffers().size());
                        for (int i = 0; i < count; i++) {
                            var offer = villager.getOffers().get(i);
                            ItemStack secondCost = offer.getDisplayedSecondBuyItem();
                            sb.append(String.format("%d:%s x%d -> %s x%d%s", i,
                                    Registries.ITEM.getId(offer.getDisplayedFirstBuyItem().getItem()),
                                    offer.getDisplayedFirstBuyItem().getCount(),
                                    Registries.ITEM.getId(offer.getSellItem().getItem()), offer.getSellItem().getCount(),
                                    offer.isDisabled() ? "(out of stock)" : ""));
                            if (!secondCost.isEmpty()) sb.append(" + ").append(Registries.ITEM.getId(secondCost.getItem())).append(" x").append(secondCost.getCount());
                            if (i + 1 < count) sb.append(",");
                        }
                        sb.append("]");
                        villagerCount++;
                    }
                }

                sb.append("; Bob's inventory: ");
                int listedStacks = 0;
                ServerPlayerEntity bob = fakePlayer instanceof ServerPlayerEntity fake ? fake : null;
                if (bob == null) sb.append("not spawned yet");
                else for (ItemStack stack : bob.getInventory().main) {
                    if (stack.isEmpty()) continue;
                    if (listedStacks++ >= 24) { sb.append("more items omitted"); break; }
                    sb.append(Registries.ITEM.getId(stack.getItem())).append(" x").append(stack.getCount()).append(", ");
                }

                if (bob != null) {
                    sb.append("; dropped items close to Bob: ");
                    int droppedCount = 0;
                    for (net.minecraft.entity.ItemEntity dropped : bob.getServerWorld().getEntitiesByClass(
                            net.minecraft.entity.ItemEntity.class, bob.getBoundingBox().expand(3), entity -> entity.isAlive())) {
                        if (droppedCount++ >= 8) break;
                        sb.append(Registries.ITEM.getId(dropped.getStack().getItem())).append(" x")
                                .append(dropped.getStack().getCount()).append("; ");
                    }
                }

                sb.append("; nearby resources: ");
                int listedBlocks = 0;
                BlockPos origin = p.getBlockPos();
                for (BlockPos pos : BlockPos.iterateOutwards(origin, 8, 3, 8)) {
                    if (listedBlocks >= 28) break;
                    if (!p.getWorld().isChunkLoaded(pos)) continue;
                    var state = p.getServerWorld().getBlockState(pos);
                    if (state.isAir() || state.getHardness(p.getWorld(), pos) < 0) continue;
                    String id = Registries.BLOCK.getId(state.getBlock()).toString();
                    boolean useful = id.contains("_ore") || id.endsWith("_log") || id.endsWith("_stem")
                            || id.endsWith(":stone") || id.endsWith(":cobblestone") || id.endsWith(":dirt")
                            || id.endsWith(":sand") || id.endsWith(":gravel") || id.endsWith(":clay")
                            || state.isIn(BlockTags.BEDS) || id.endsWith(":crafting_table")
                            || id.endsWith(":furnace") || id.endsWith(":water") || id.endsWith(":lava")
                            || id.endsWith(":chest") || id.endsWith(":barrel");
                    if (!useful) continue;
                    sb.append(id).append(" at ").append(pos.getX()).append(",").append(pos.getY()).append(",").append(pos.getZ()).append("; ");
                    listedBlocks++;
                }
                return sb.toString();
            } catch (Throwable t) {
                return "in Minecraft";
            }
        }
    }
}
