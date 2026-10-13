package com.quackcraft.quackingly.companion;

import com.quackcraft.quackingly.Quackingly;
import com.quackcraft.quackingly.config.QuackinglyConfig;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.util.Hand;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * Tick-based brain for the Quackingly companion.
 *
 * Every server tick we:
 *   1. Find the host player's session.
 *   2. If the companion is in a different dimension or very far (>32 blocks), teleport.
 *   3. Otherwise, walk toward the host using velocity (smooth, no teleport jerks).
 *   4. If close (<4 blocks), stop and look at the host.
 *
 * Why manual velocity instead of EntityNavigation:
 *   Carpet's EntityPlayerMPFake extends ServerPlayerEntity, which is NOT a
 *   PathAwareEntity. So we can't use mob-style pathfinding on it. Instead,
 *   we set the velocity vector toward the host each tick — MC's physics
 *   handles the rest (collision, gravity, jumping). This gives smooth,
 *   collision-aware movement without teleport jerks.
 *
 * Token optimisation: this is all tick-based and local — no LLM calls.
 */
public final class CompanionBrain {

    private CompanionBrain() {}

    private static final double FOLLOW_STOP  = 4.0;
    private static final double TELEPORT_DIST = 32.0;
    private static final double WALK_SPEED = 0.25;
    private static final double SPRINT_SPEED = 0.35;
    private static int tickCounter;

    public static void registerTickHook() {
        ServerTickEvents.END_SERVER_TICK.register(CompanionBrain::onServerTick);
    }

    private static void onServerTick(MinecraftServer server) {
        // Follow decisions are local and inexpensive; checking every fifth tick
        // keeps idle companion overhead low while preserving smooth movement.
        if (++tickCounter % 5 != 0) return;
        CompanionManager mgr = CompanionManager.getInstance();
        for (CompanionManager.CompanionSession session : new java.util.ArrayList<>(mgr.sessionsView())) {
            try {
                tickSession(session);
            } catch (Throwable t) {
                Quackingly.LOGGER.debug("[Quackingly] Brain tick failed: {}", t.toString());
            }
        }
    }

    private static void tickSession(CompanionManager.CompanionSession session) {
        PlayerEntity companion = session.getFakePlayer();
        ServerPlayerEntity host = session.getHost();
        if (companion == null || host == null) return;
        if (companion.isRemoved() || host.isRemoved()) return;
        if (companion instanceof ServerPlayerEntity bob) BobActionExecutor.collectNearbyDrops(bob);
        if (companion.getWorld() != host.getWorld()) {
            // Different dimensions — teleport to host
            try {
                companion.teleport(
                        host.getServerWorld(),
                        host.getX(), host.getY(), host.getZ(),
                        java.util.Set.of(),
                        host.getYaw(), host.getPitch());
            } catch (Throwable t) {
                Quackingly.LOGGER.debug("[Quackingly] cross-dimension teleport failed: {}", t.toString());
            }
            return;
        }

        Vec3d cPos = companion.getPos();
        Vec3d hPos = host.getPos();
        double dist = cPos.distanceTo(hPos);

        if (companion instanceof ServerPlayerEntity bob && bob.getHungerManager().getFoodLevel() <= 8
                && tickCounter % 100 == 0) {
            String result = BobActionExecutor.eat(host, bob);
            if (result.startsWith("I ate")) host.sendMessage(net.minecraft.text.Text.literal("[Bob] " + result));
        }

        if (QuackinglyConfig.get().autoSleep && !companion.isSleeping() && tickCounter % 100 == 0
                && host.getServerWorld().getDimension().bedWorks() && host.getServerWorld().isNight()) {
            boolean dangerNearHost = !host.getServerWorld().getEntitiesByClass(
                    HostileEntity.class, host.getBoundingBox().expand(8), HostileEntity::isAlive).isEmpty();
            if (!dangerNearHost && companion instanceof ServerPlayerEntity bob) {
                String sleepResult = BobActionExecutor.sleep(host, bob);
                if (sleepResult.startsWith("I found")) host.sendMessage(net.minecraft.text.Text.literal("[Bob] " + sleepResult));
            }
        }

        // Too far — teleport behind host (avoids long pathfinding)
        if (dist > TELEPORT_DIST) {
            Vec3d behind = hPos.add(Vec3d.fromPolar(0, host.getYaw() + 180).multiply(2));
            companion.requestTeleport(behind.x, behind.y, behind.z);
            return;
        }

        // Look at host (always, if config allows)
        if (QuackinglyConfig.get().lookAtPlayer) {
            lookAt(companion, host.getEyePos());
        }

        // Defensive help stays local: alert about nearby hostiles, then swing only
        // when they are within normal melee distance. No model call is made here.
        if (QuackinglyConfig.get().autoDefend) {
            if ((host.isOnFire() || host.isInLava()) && session.canWarnAboutThreat(tickCounter)) {
                String hazard = host.isInLava() ? "You're in lava! Get out now." : "You're on fire! Get to water.";
                host.sendMessage(net.minecraft.text.Text.literal("[Bob] " + hazard));
                session.markThreatWarning(tickCounter);
            } else if (host.isTouchingWater() && host.getAir() <= 100 && session.canWarnAboutThreat(tickCounter)) {
                host.sendMessage(net.minecraft.text.Text.literal("[Bob] You're running out of air! Get to the surface."));
                session.markThreatWarning(tickCounter);
            } else if (host.getHealth() <= 6 && session.canWarnAboutThreat(tickCounter)) {
                host.sendMessage(net.minecraft.text.Text.literal("[Bob] You're low on health; find cover and eat."));
                session.markThreatWarning(tickCounter);
            }
            HostileEntity threat = null;
            double nearest = Double.MAX_VALUE;
            for (HostileEntity candidate : host.getServerWorld().getEntitiesByClass(
                    HostileEntity.class, host.getBoundingBox().expand(12), HostileEntity::isAlive)) {
                double distance = candidate.squaredDistanceTo(host);
                if (distance < nearest) {
                    nearest = distance;
                    threat = candidate;
                }
            }
            if (threat != null) {
                if (session.canWarnAboutThreat(tickCounter)) {
                    host.sendMessage(net.minecraft.text.Text.literal(
                            "[Bob] Watch out! A " + threat.getType().getName().getString() + " is nearby."));
                    session.markThreatWarning(tickCounter);
                }
                double threatDistance = companion.squaredDistanceTo(threat);
                if (threat instanceof CreeperEntity && threat.squaredDistanceTo(host) < 7 * 7) {
                    Vec3d away = companion.getPos().subtract(threat.getPos()).normalize();
                    companion.setVelocity(away.x * WALK_SPEED, companion.getVelocity().y, away.z * WALK_SPEED);
                    companion.velocityModified = true;
                    return;
                }
                if (threatDistance <= 3.5 * 3.5 && companion instanceof net.minecraft.server.network.ServerPlayerEntity bob
                        && session.canAttackForDefense(tickCounter)) {
                    if (BobActionExecutor.defend(host, bob, threat)) session.markDefenseAttack(tickCounter);
                } else if (session.isFollowing() && threatDistance <= 12 * 12) {
                    lookAt(companion, threat.getEyePos());
                    walkToward(companion, threat.getPos(), Math.sqrt(threatDistance));
                    return;
                }
            }
        }

        // Active gathering temporarily takes movement priority over following.
        // The task still yields above to local danger responses.
        if (companion instanceof ServerPlayerEntity bob && BobActionExecutor.tickMining(bob)) return;

        // Far enough to walk over (and following is enabled)
        if (dist > FOLLOW_STOP && session.isFollowing()) {
            walkToward(companion, hPos, dist);
        } else {
            // Close enough — stop moving
            companion.setVelocity(0, companion.getVelocity().y, 0);
            companion.velocityModified = true;
            companion.setSprinting(false);
            companion.setSneaking(false);
        }
    }

    static void lookAt(PlayerEntity companion, Vec3d target) {
        Vec3d eye = companion.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        // Smoothly interpolate by setting directly; MC will handle client-side smoothing
        companion.setYaw(yaw);
        companion.setHeadYaw(yaw);
        companion.setPitch(pitch);
    }

    static void walkToward(PlayerEntity companion, Vec3d target, double dist) {
        Vec3d pos = companion.getPos();
        double dx = target.x - pos.x;
        double dz = target.z - pos.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.1) return;

        // Sprint if far, walk if closer — feels more natural
        boolean shouldSprint = dist > 10.0;
        double speed = shouldSprint ? SPRINT_SPEED : WALK_SPEED;
        double vx = (dx / len) * speed;
        double vz = (dz / len) * speed;
        double vy = companion.getVelocity().y;
        if (companion.isTouchingWater()) {
            double verticalDifference = target.y - pos.y;
            vy = Math.max(-0.12, Math.min(0.12, verticalDifference * 0.10));
            if (companion.getAir() < 80) vy = Math.max(vy, 0.18);
        }
        companion.setVelocity(vx, vy, vz);
        companion.velocityModified = true;
        companion.setSprinting(shouldSprint);
        companion.setSneaking(false);

        // If we're hitting a wall (small horizontal movement), try to jump
        Vec3d curVel = companion.getVelocity();
        if (companion.horizontalCollision && companion.isOnGround()) {
            companion.setVelocity(curVel.x, 0.42, curVel.z);  // jump
            companion.velocityModified = true;
        }
    }
}
