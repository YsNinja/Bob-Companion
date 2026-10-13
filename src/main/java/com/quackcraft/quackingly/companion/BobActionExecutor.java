package com.quackcraft.quackingly.companion;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.TradeOffer;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.function.Consumer;

/** Executes only small, validated actions explicitly requested in chat. */
public final class BobActionExecutor {
    private static final Map<UUID, MiningJob> MINING_JOBS = new HashMap<>();

    private BobActionExecutor() {}

    public static List<String> execute(ServerPlayerEntity host, ServerPlayerEntity bob,
                                        BobActionPlan plan, String userRequest, Consumer<Boolean> setFollowOverride) {
        List<String> results = new ArrayList<>();
        int placements = 0;
        for (BobActionPlan.Action action : plan.actions()) {
            if (action.type().equals("place_block") && placements >= 4) {
                results.add("I limited this build to four blocks at a time.");
                continue;
            }
            if (action.type().equals("give_items") && !explicitlyRequestedHandover(userRequest)) {
                results.add("I only hand items over when you ask me directly.");
                continue;
            }
            String result = switch (action.type()) {
                case "mine_block" -> mine(host, bob, action);
                case "gather" -> gather(host, bob, action);
                case "place_block" -> place(host, bob, action);
                case "build_structure" -> build(host, bob, action);
                case "attack" -> attack(host, bob, action);
                case "trade" -> trade(host, bob, action);
                case "sleep" -> sleep(host, bob);
                case "eat" -> eat(host, bob);
                case "give_items" -> giveItems(host, bob, action);
                case "follow" -> { setFollowOverride.accept(action.enabled()); yield action.enabled() ? "I'll follow you." : "I'll stay nearby."; }
                case "stay" -> { setFollowOverride.accept(false); yield "I'll wait here."; }
                case "come_here" -> { comeHere(host, bob); setFollowOverride.accept(true); yield "Coming over."; }
                default -> "I don't know how to do that yet.";
            };
            if (action.type().equals("place_block") && result.startsWith("Placed ")) placements++;
            results.add(result);
        }
        if (!results.isEmpty()) {
            host.sendMessage(Text.literal("[Bob] " + String.join(" ", results)));
        }
        return results;
    }

    static boolean explicitlyRequestedHandover(String request) {
        if (request == null || request.isBlank()) return false;
        String normalized = request.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}' ]", " ").replaceAll("\\s+", " ").trim();
        if (Pattern.compile("\\b(?:don't|do not|never|stop|not)\\b.{0,32}\\b(?:give|hand|return|pass|bring)\\b")
                .matcher(normalized).find()) return false;
        return Pattern.compile("\\b(?:give|hand|return|pass|bring)\\s+(?:me|us)\\b")
                .matcher(normalized).find()
                || Pattern.compile("\\b(?:can|could|would)\\s+you\\s+(?:please\\s+)?(?:give|hand|return|pass|bring)\\s+(?:me|us)\\b")
                .matcher(normalized).find()
                || Pattern.compile("\\b(?:can|could|may)\\s+i\\s+have\\b")
                .matcher(normalized).find()
                || Pattern.compile("\\b(?:give|hand|return|pass)\\s+my\\s+(?:items?|things?|stuff|materials?)\\b")
                .matcher(normalized).find();
    }

    private static String mine(ServerPlayerEntity host, ServerPlayerEntity bob, BobActionPlan.Action action) {
        BlockPos origin = new BlockPos(action.x(), action.y(), action.z());
        if (!inRange(host, origin, 24) || bob.squaredDistanceTo(Vec3d.ofCenter(origin)) > 24 * 24) return "That block is too far away; move closer first.";
        if (!host.getWorld().isChunkLoaded(origin)) return "That block is in an unloaded area.";
        var world = bob.getServerWorld();
        BlockState firstState = world.getBlockState(origin);
        if (!action.block().isBlank()) {
            Identifier requestedId = Identifier.tryParse(action.block());
            if (requestedId == null || !Registries.BLOCK.getId(firstState.getBlock()).equals(requestedId)) {
                return "That isn't the block type I was asked to mine.";
            }
        }
        if (!isSafeMineTarget(world, host, bob, origin, firstState)) return "I won't break that block or put either of us at risk.";
        return queueMining(host, bob, origin, firstState.getBlock(), action.count());
    }

    private static String gather(ServerPlayerEntity host, ServerPlayerEntity bob, BobActionPlan.Action action) {
        Identifier requested = Identifier.tryParse(action.block());
        if (requested == null || !Registries.BLOCK.containsId(requested)) return "Tell me the block type you want me to gather.";
        Block targetBlock = Registries.BLOCK.get(requested);
        BlockPos closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterateOutwards(bob.getBlockPos(), 16, 5, 16)) {
            if (!bob.getServerWorld().isChunkLoaded(pos) || !bob.getServerWorld().getBlockState(pos).isOf(targetBlock)) continue;
            if (!inRange(host, pos, 24) || !isSafeMineTarget(bob.getServerWorld(), host, bob, pos, bob.getServerWorld().getBlockState(pos))) continue;
            double distance = bob.squaredDistanceTo(Vec3d.ofCenter(pos));
            if (distance < closestDistance) { closest = pos.toImmutable(); closestDistance = distance; }
        }
        if (closest == null) return "I can't find safe " + requested + " blocks in the nearby area. Tell me where to look or drop supplies near me.";
        return queueMining(host, bob, closest, targetBlock, action.count());
    }

    private static boolean isSafeMineTarget(net.minecraft.server.world.ServerWorld world, ServerPlayerEntity host,
                                            ServerPlayerEntity bob, BlockPos pos, BlockState state) {
        return inRange(host, pos, 24) && !overlapsPlayer(pos, host, bob) && !state.isAir()
                && state.getHardness(world, pos) >= 0 && world.getBlockEntity(pos) == null
                && !state.isOf(Blocks.BEDROCK) && !state.isOf(Blocks.BARRIER)
                && !state.isOf(Blocks.END_PORTAL) && !state.isOf(Blocks.END_GATEWAY)
                && state.getFluidState().isEmpty();
    }

    private static String queueMining(ServerPlayerEntity host, ServerPlayerEntity bob, BlockPos origin, Block block, int requestedCount) {
        if (MINING_JOBS.containsKey(bob.getUuid())) return "I'm already working on a gathering task. I'll finish that first.";
        int wanted = Math.max(1, Math.min(32, requestedCount));
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(origin.toImmutable());
        seen.add(origin.toImmutable());
        List<BlockPos> targets = new ArrayList<>();
        var world = bob.getServerWorld();
        while (!queue.isEmpty() && targets.size() < wanted && seen.size() <= 512) {
            BlockPos pos = queue.removeFirst();
            BlockState state = world.getBlockState(pos);
            if (!world.isChunkLoaded(pos) || !state.isOf(block) || !isSafeMineTarget(world, host, bob, pos, state)) continue;
            if (requiresTool(state) && bestToolSlot(bob, state) < 0) {
                return "I need a suitable tool in my own inventory to mine " + Registries.BLOCK.getId(block) + ". Drop me a tool if you want me to continue.";
            }
            targets.add(pos.toImmutable());
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.offset(direction);
                if (next.getManhattanDistance(origin) <= 5 && seen.add(next)) queue.addLast(next);
            }
        }
        if (targets.isEmpty()) return "I couldn't find safe matching blocks to gather.";
        int heldSlot = bestToolSlot(bob, world.getBlockState(targets.get(0)));
        if (requiresTool(world.getBlockState(targets.get(0))) && heldSlot < 0) return "I need the right tool in my inventory first.";
        MINING_JOBS.put(bob.getUuid(), new MiningJob(host, block, targets));
        return "I'll walk over and mine up to " + targets.size() + " " + Registries.BLOCK.getId(block) + " blocks using my own inventory.";
    }

    /** Advances one bounded survival gathering job. Called on the server thread. */
    public static boolean tickMining(ServerPlayerEntity bob) {
        MiningJob job = MINING_JOBS.get(bob.getUuid());
        if (job == null) return false;
        ServerPlayerEntity host = job.host;
        var world = bob.getServerWorld();
        if (bob.isRemoved() || host.isRemoved() || bob.getWorld() != host.getWorld() || ++job.elapsedTicks > 20 * 180) {
            cancelMining(bob, "I stopped gathering because the trip took too long or we separated.");
            return false;
        }
        if (!world.getEntitiesByClass(HostileEntity.class, bob.getBoundingBox().expand(7), HostileEntity::isAlive).isEmpty()) {
            bob.setVelocity(0, bob.getVelocity().y, 0);
            bob.velocityModified = true;
            return true;
        }

        if (job.pickupPos != null) {
            if (bob.squaredDistanceTo(Vec3d.ofCenter(job.pickupPos)) > 2.25) {
                walkToMiningTarget(bob, job.pickupPos);
                return true;
            }
            job.pickupPos = null;
        }

        while (!job.targets.isEmpty()) {
            BlockPos target = job.targets.peekFirst();
            if (!world.isChunkLoaded(target) || !inRange(host, target, 24)
                    || !world.getBlockState(target).isOf(job.block)
                    || !isSafeMineTarget(world, host, bob, target, world.getBlockState(target))) {
                if (job.targetInProgress != null) world.setBlockBreakingInfo(bob.getId(), job.targetInProgress, -1);
                job.targetInProgress = null;
                job.targets.removeFirst();
                job.progress = 0;
                continue;
            }

            double distance = bob.squaredDistanceTo(Vec3d.ofCenter(target));
            if (distance > 3.2 * 3.2) {
                walkToMiningTarget(bob, target);
                return true;
            }
            var ray = world.raycast(new net.minecraft.world.RaycastContext(bob.getEyePos(), Vec3d.ofCenter(target),
                    net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
                    net.minecraft.world.RaycastContext.FluidHandling.NONE, bob));
            if (ray.getType() != net.minecraft.util.hit.HitResult.Type.BLOCK || !ray.getBlockPos().equals(target)) {
                cancelMining(bob, "That block is obstructed, so I can't reach it safely with my current movement.");
                return false;
            }

            BlockState state = world.getBlockState(target);
            job.targetInProgress = target.toImmutable();
            int toolSlot = bestToolSlot(bob, state);
            if (requiresTool(state) && toolSlot < 0) {
                cancelMining(bob, "I need the right tool in my own inventory to keep mining. Drop one close to me if you want me to continue.");
                return false;
            }
            float delta = withBobTool(bob, state, () -> state.calcBlockBreakingDelta(bob, world, target));
            if (delta <= 0) {
                cancelMining(bob, "I can't make progress on that block with the tools I have.");
                return false;
            }
            job.progress += delta * 5.0f;
            world.setBlockBreakingInfo(bob.getId(), target, Math.min(9, (int) (job.progress * 10.0f)));
            if (job.progress < 1.0f) return true;

            world.setBlockBreakingInfo(bob.getId(), target, -1);
            if (!breakWithBobTool(bob, target, state)) {
                cancelMining(bob, "I couldn't finish mining that block safely.");
                return false;
            }
            bob.swingHand(Hand.MAIN_HAND);
            job.targets.removeFirst();
            job.mined++;
            job.progress = 0;
            job.targetInProgress = null;
            job.pickupPos = target.toImmutable();
            return true;
        }

        MINING_JOBS.remove(bob.getUuid());
        host.sendMessage(Text.literal("[Bob] I gathered " + job.mined + " " + Registries.BLOCK.getId(job.block) + " blocks. The drops are in my inventory if I could reach them."));
        return false;
    }

    private static void walkToMiningTarget(ServerPlayerEntity bob, BlockPos target) {
        Vec3d goal = Vec3d.ofCenter(target);
        double distance = bob.getPos().distanceTo(goal);
        CompanionBrain.lookAt(bob, goal);
        CompanionBrain.walkToward(bob, goal, distance);
    }

    private static void cancelMining(ServerPlayerEntity bob, String message) {
        MiningJob job = MINING_JOBS.remove(bob.getUuid());
        if (job != null) {
            if (job.targetInProgress != null && bob.getWorld().isChunkLoaded(job.targetInProgress)) {
                bob.getServerWorld().setBlockBreakingInfo(bob.getId(), job.targetInProgress, -1);
            }
            job.host.sendMessage(Text.literal("[Bob] " + message));
        }
    }

    private static float withBobTool(ServerPlayerEntity bob, BlockState state, java.util.function.Supplier<Float> operation) {
        int slot = bestToolSlot(bob, state);
        PlayerInventory inventory = bob.getInventory();
        int heldSlot = inventory.selectedSlot;
        if (slot < 0 || slot == heldSlot) return operation.get();
        ItemStack held = inventory.getStack(heldSlot);
        ItemStack tool = inventory.getStack(slot);
        inventory.setStack(slot, held);
        inventory.setStack(heldSlot, tool);
        try { return operation.get(); }
        finally {
            ItemStack usedTool = inventory.getStack(heldSlot);
            ItemStack originalHeld = inventory.getStack(slot);
            inventory.setStack(slot, usedTool);
            inventory.setStack(heldSlot, originalHeld);
        }
    }

    private static final class MiningJob {
        private final ServerPlayerEntity host;
        private final Block block;
        private final ArrayDeque<BlockPos> targets;
        private BlockPos pickupPos;
        private BlockPos targetInProgress;
        private float progress;
        private int mined;
        private int elapsedTicks;

        private MiningJob(ServerPlayerEntity host, Block block, List<BlockPos> targets) {
            this.host = host;
            this.block = block;
            this.targets = new ArrayDeque<>(targets);
        }
    }

    private static boolean breakWithBobTool(ServerPlayerEntity bob, BlockPos pos, BlockState state) {
        int slot = bestToolSlot(bob, state);
        if (requiresTool(state) && slot < 0) return false;
        PlayerInventory inventory = bob.getInventory();
        int heldSlot = inventory.selectedSlot;
        ItemStack held = inventory.getStack(heldSlot);
        if (slot >= 0 && slot != heldSlot) {
            ItemStack tool = inventory.getStack(slot);
            inventory.setStack(slot, held);
            inventory.setStack(heldSlot, tool);
        }
        try {
            boolean broken = bob.interactionManager.tryBreakBlock(pos);
            bob.swingHand(Hand.MAIN_HAND);
            return broken;
        } finally {
            if (slot >= 0 && slot != heldSlot) {
                ItemStack usedTool = inventory.getStack(heldSlot);
                ItemStack originalHeld = inventory.getStack(slot);
                inventory.setStack(slot, usedTool);
                inventory.setStack(heldSlot, originalHeld);
            }
        }
    }

    private static boolean requiresTool(BlockState state) {
        return state.isIn(BlockTags.PICKAXE_MINEABLE) || state.isIn(BlockTags.AXE_MINEABLE)
                || state.isIn(BlockTags.SHOVEL_MINEABLE) || state.isIn(BlockTags.HOE_MINEABLE);
    }

    private static int bestToolSlot(ServerPlayerEntity owner, BlockState state) {
        int bestSlot = -1;
        float bestSpeed = 1.0f;
        for (int slot = 0; slot < owner.getInventory().main.size(); slot++) {
            ItemStack stack = owner.getInventory().getStack(slot);
            boolean matches = (state.isIn(BlockTags.PICKAXE_MINEABLE) && stack.isIn(ItemTags.PICKAXES))
                    || (state.isIn(BlockTags.AXE_MINEABLE) && stack.isIn(ItemTags.AXES))
                    || (state.isIn(BlockTags.SHOVEL_MINEABLE) && stack.isIn(ItemTags.SHOVELS))
                    || (state.isIn(BlockTags.HOE_MINEABLE) && stack.isIn(ItemTags.HOES));
            if (!matches) continue;
            float speed = stack.getMiningSpeedMultiplier(state);
            if (speed > bestSpeed) { bestSpeed = speed; bestSlot = slot; }
        }
        return bestSlot;
    }

    private static String place(ServerPlayerEntity host, ServerPlayerEntity bob, BobActionPlan.Action action) {
        Identifier id = Identifier.tryParse(action.block());
        Block block = resolveBuildBlock(action.block());
        if (block == null) return "I don't recognize or can't place that block.";
        BlockPos pos = new BlockPos(action.x(), action.y(), action.z());
        var world = host.getServerWorld();
        if (!inRange(host, pos, 6) || !world.isChunkLoaded(pos)) return "That build spot is too far away.";
        if (!world.getBlockState(pos).isReplaceable() || !world.getWorldBorder().contains(pos)) return "That spot is occupied or outside the world border.";
        if (overlapsPlayer(pos, host, bob)) return "That block would trap one of us; choose another spot.";

        ItemStack payment = findOne(bob.getInventory(), block.asItem());
        if (payment == null) return "I need one " + id + " in my inventory. Drop it near me or ask me to gather the material first.";
        if (!world.setBlockState(pos, block.getDefaultState())) return "I couldn't place a block there.";
        payment.decrement(1);
        return "Placed " + Registries.BLOCK.getId(block) + " at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ".";
    }

    private static String build(ServerPlayerEntity host, ServerPlayerEntity bob, BobActionPlan.Action action) {
        Block block = resolveBuildBlock(action.block());
        if (block == null) return "I don't recognize or can't place that block.";
        int size = Math.max(2, Math.min(8, action.size()));
        int height = Math.max(2, Math.min(5, action.height()));
        BlockPos origin = new BlockPos(action.x(), action.y(), action.z());
        Direction forward = host.getHorizontalFacing();
        Direction right = forward.rotateYClockwise();
        List<BlockPos> layout = new ArrayList<>();

        switch (action.pattern()) {
            case "wall" -> {
                for (int w = 0; w < size; w++) for (int h = 0; h < height; h++) {
                    layout.add(origin.offset(right, w).up(h));
                }
            }
            case "platform" -> {
                for (int w = 0; w < size; w++) for (int d = 0; d < size; d++) {
                    layout.add(origin.offset(right, w).offset(forward, d));
                }
            }
            case "hut" -> {
                size = 5;
                height = 3;
                for (int w = 0; w < size; w++) for (int d = 0; d < size; d++) {
                    layout.add(origin.offset(right, w).offset(forward, d));
                    layout.add(origin.offset(right, w).offset(forward, d).up(height + 1));
                }
                for (int w = 0; w < size; w++) for (int h = 1; h <= height; h++) {
                    if (w == 0 || w == size - 1) {
                        layout.add(origin.offset(right, w).up(h));
                        layout.add(origin.offset(right, w).offset(forward, size - 1).up(h));
                    }
                    if (h > 2 || w != size / 2) {
                        layout.add(origin.offset(forward, 0).offset(right, w).up(h));
                        layout.add(origin.offset(forward, size - 1).offset(right, w).up(h));
                    }
                }
            }
            default -> { return "I can build a wall, platform, or small hut."; }
        }

        layout = new ArrayList<>(new LinkedHashSet<>(layout));
        if (layout.isEmpty() || layout.size() > 100) return "That build is too large for one request.";
        var world = host.getServerWorld();
        for (BlockPos pos : layout) {
            if (!inRange(host, pos, 8) || !world.getWorldBorder().contains(pos) || !world.isChunkLoaded(pos)) {
                return "That structure would be outside my safe building area.";
            }
            if (!world.getBlockState(pos).isReplaceable()) return "There's something in the way; I won't overwrite it.";
            if (overlapsPlayer(pos, host, bob)) return "The structure would overlap one of us; choose a clear spot.";
        }
        int available = bob.getInventory().main.stream().filter(stack -> stack.getItem() == block.asItem())
                .mapToInt(ItemStack::getCount).sum();
        if (available < layout.size()) return "I need " + layout.size() + " " + action.block() + " for that build; I have " + available + ". I can gather more first, or you can drop some near me.";

        int built = 0;
        for (BlockPos pos : layout) {
            if (!world.setBlockState(pos, block.getDefaultState())) continue;
            ItemStack payment = findOne(bob.getInventory(), block.asItem());
            if (payment != null) payment.decrement(1);
            built++;
        }
        return "Built a " + action.pattern() + " with " + built + " " + action.block() + " blocks.";
    }

    private static Block resolveBuildBlock(String rawId) {
        Identifier id = Identifier.tryParse(rawId);
        if (id == null || !Registries.BLOCK.containsId(id)) return null;
        Block block = Registries.BLOCK.get(id);
        if (block == Blocks.AIR || block == Blocks.TNT || block == Blocks.LAVA || block == Blocks.WATER
                || block == Blocks.FIRE || block == Blocks.SOUL_FIRE || block.getDefaultState().hasBlockEntity()
                || block.asItem() == Items.AIR) return null;
        return block;
    }

    private static String attack(ServerPlayerEntity host, ServerPlayerEntity bob, BobActionPlan.Action action) {
        Entity entity = host.getServerWorld().getEntityById(action.entityId());
        if (!(entity instanceof HostileEntity hostile) || !hostile.isAlive()) return "I can't find that hostile mob.";
        if (hostile.squaredDistanceTo(host) > 18 * 18 || hostile.squaredDistanceTo(bob) > 5 * 5) return "That mob is too far away for me to hit safely.";
        attackWithBobWeapon(bob, hostile);
        return "I'm fighting the " + hostile.getType().getName().getString() + ".";
    }

    public static boolean defend(ServerPlayerEntity host, ServerPlayerEntity bob, HostileEntity hostile) {
        if (hostile == null || !hostile.isAlive() || hostile.squaredDistanceTo(bob) > 3.5 * 3.5) return false;
        attackWithBobWeapon(bob, hostile);
        return true;
    }

    private static void attackWithBobWeapon(ServerPlayerEntity bob, HostileEntity target) {
        int slot = bestWeaponSlot(bob);
        PlayerInventory inventory = bob.getInventory();
        int heldSlot = inventory.selectedSlot;
        ItemStack held = inventory.getStack(heldSlot);
        if (slot >= 0 && slot != heldSlot) {
            ItemStack weapon = inventory.getStack(slot);
            inventory.setStack(slot, held);
            inventory.setStack(heldSlot, weapon);
        }
        try { bob.attack(target); bob.swingHand(Hand.MAIN_HAND); }
        finally {
            if (slot >= 0 && slot != heldSlot) {
                ItemStack usedWeapon = inventory.getStack(heldSlot);
                ItemStack originalHeld = inventory.getStack(slot);
                inventory.setStack(slot, usedWeapon);
                inventory.setStack(heldSlot, originalHeld);
            }
        }
    }

    private static int bestWeaponSlot(ServerPlayerEntity owner) {
        int best = -1;
        float damage = 1.0f;
        for (int slot = 0; slot < owner.getInventory().main.size(); slot++) {
            ItemStack stack = owner.getInventory().getStack(slot);
            if (!stack.isIn(ItemTags.SWORDS) && !stack.isIn(ItemTags.AXES)) continue;
            float candidate = stack.getMiningSpeedMultiplier(Blocks.COBWEB.getDefaultState());
            if (candidate > damage) { damage = candidate; best = slot; }
        }
        return best;
    }

    private static String trade(ServerPlayerEntity host, ServerPlayerEntity bob, BobActionPlan.Action action) {
        Entity entity = host.getServerWorld().getEntityById(action.entityId());
        if (!(entity instanceof VillagerEntity villager) || villager.squaredDistanceTo(bob) > 36) return "I can't reach that villager.";
        var offers = villager.getOffers();
        if (action.offerIndex() < 0 || action.offerIndex() >= offers.size()) return "I can't find that trade.";
        TradeOffer offer = offers.get(action.offerIndex());
        if (offer.isDisabled()) return "That villager's trade is out of stock.";

        ItemStack firstCost = offer.getDisplayedFirstBuyItem().copy();
        ItemStack secondCost = offer.getDisplayedSecondBuyItem().copy();
        if (!hasCost(bob.getInventory(), firstCost, secondCost)) return "I don't have the items for that trade. I'll need to gather them or you can drop them near me.";
        removeCost(bob.getInventory(), firstCost);
        removeCost(bob.getInventory(), secondCost);
        villager.setCustomer(bob);
        try {
            villager.trade(offer);
        } finally {
            villager.setCustomer(null);
        }
        ItemStack reward = offer.getSellItem().copy();
        if (!bob.getInventory().insertStack(reward) && !reward.isEmpty()) bob.dropItem(reward, false);
        return "Traded with the villager; I put the purchase in my inventory.";
    }

    private static boolean hasCost(PlayerInventory inventory, ItemStack first, ItemStack second) {
        for (ItemStack cost : List.of(first, second)) {
            if (cost.isEmpty()) continue;
            int count = inventory.main.stream().filter(stack -> stack.getItem() == cost.getItem()).mapToInt(ItemStack::getCount).sum();
            long sameCostCount = List.of(first, second).stream().filter(item -> !item.isEmpty() && item.getItem() == cost.getItem()).mapToLong(ItemStack::getCount).sum();
            if (count < sameCostCount) return false;
        }
        return true;
    }

    private static void removeCost(PlayerInventory inventory, ItemStack cost) {
        int remaining = cost.getCount();
        for (ItemStack stack : inventory.main) {
            if (remaining <= 0) break;
            if (stack.getItem() != cost.getItem()) continue;
            int removed = Math.min(remaining, stack.getCount());
            stack.decrement(removed);
            remaining -= removed;
        }
    }

    private static ItemStack findOne(PlayerInventory inventory, net.minecraft.item.Item item) {
        for (ItemStack stack : inventory.main) if (!stack.isEmpty() && stack.getItem() == item) return stack;
        return null;
    }

    private static boolean inRange(ServerPlayerEntity host, BlockPos pos, int range) {
        return host.squaredDistanceTo(Vec3d.ofCenter(pos)) <= range * range;
    }

    private static boolean overlapsPlayer(BlockPos pos, ServerPlayerEntity host, ServerPlayerEntity bob) {
        Box cell = new Box(pos);
        return host.getBoundingBox().intersects(cell) || bob.getBoundingBox().intersects(cell);
    }

    private static void comeHere(ServerPlayerEntity host, ServerPlayerEntity bob) {
        var back = host.getHorizontalFacing().getOpposite();
        BlockPos target = host.getBlockPos().offset(back, 2);
        if (host.getServerWorld().getBlockState(target).isAir()) bob.requestTeleport(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
    }

    public static String sleep(ServerPlayerEntity host, ServerPlayerEntity bob) {
        var world = bob.getServerWorld();
        if (!world.getDimension().bedWorks()) return "Beds aren't safe in this dimension.";
        if (!world.isNight()) return "It's not night yet; I'll sleep when it gets dark.";
        for (BlockPos pos : BlockPos.iterateOutwards(host.getBlockPos(), 12, 4, 12)) {
            if (!world.getBlockState(pos).isIn(BlockTags.BEDS)) continue;
            if (bob.squaredDistanceTo(Vec3d.ofCenter(pos)) > 16 * 16) continue;
            var result = bob.trySleep(pos);
            if (result.right().isPresent()) return "I found a bed and went to sleep.";
        }
        return "I can't find a safe, reachable bed nearby.";
    }

    public static String eat(ServerPlayerEntity host, ServerPlayerEntity bob) {
        if (bob.getHungerManager().getFoodLevel() >= 20) return "I'm not hungry right now.";
        for (int slot = 0; slot < bob.getInventory().main.size(); slot++) {
            ItemStack foodStack = bob.getInventory().getStack(slot);
            if (foodStack.isEmpty() || foodStack.isOf(Items.ROTTEN_FLESH) || foodStack.isOf(Items.SPIDER_EYE)
                    || foodStack.isOf(Items.POISONOUS_POTATO) || foodStack.isOf(Items.PUFFERFISH)
                    || foodStack.isOf(Items.CHICKEN)) continue;
            FoodComponent food = foodStack.get(DataComponentTypes.FOOD);
            if (food == null || (!food.canAlwaysEat() && bob.getHungerManager().getFoodLevel() >= 20)) continue;
            String foodName = Registries.ITEM.getId(foodStack.getItem()).toString();
            bob.getHungerManager().eat(food);
            foodStack.decrement(1);
            bob.getInventory().markDirty();
            return "I ate one " + foodName + " from my inventory.";
        }
        return "I don't have safe food. I'll look for food nearby, or you can drop food near me.";
    }

    /** Normal survival pickup radius: Bob only collects item entities he physically reaches. */
    public static void collectNearbyDrops(ServerPlayerEntity bob) {
        var area = bob.getBoundingBox().expand(1.25, 0.5, 1.25);
        for (net.minecraft.entity.ItemEntity dropped : bob.getServerWorld().getEntitiesByClass(
                net.minecraft.entity.ItemEntity.class, area, item -> item.isAlive() && !item.cannotPickup())) {
            ItemStack stack = dropped.getStack().copy();
            int before = stack.getCount();
            bob.getInventory().insertStack(stack);
            int pickedUp = before - stack.getCount();
            if (pickedUp <= 0) continue;
            bob.sendPickup(dropped, pickedUp);
            dropped.setStack(stack);
            if (stack.isEmpty()) dropped.discard();
        }
    }

    private static String giveItems(ServerPlayerEntity host, ServerPlayerEntity bob, BobActionPlan.Action action) {
        Identifier id = Identifier.tryParse(action.block());
        if (id == null || !Registries.ITEM.containsId(id)) return "Tell me which item you want me to hand over.";
        int remaining = Math.max(1, Math.min(64, action.count()));
        int moved = 0;
        PlayerInventory inventory = bob.getInventory();
        for (int slot = 0; slot < inventory.main.size() && remaining > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !Registries.ITEM.getId(stack.getItem()).equals(id)) continue;
            int count = Math.min(remaining, stack.getCount());
            ItemStack transfer = stack.split(count);
            if (!host.getInventory().insertStack(transfer) && !transfer.isEmpty()) host.dropItem(transfer, false);
            moved += count;
            remaining -= count;
        }
        inventory.markDirty();
        if (moved == 0) return "I don't have any " + id + " to give you.";
        return "I handed you " + moved + " " + id + ".";
    }
}
