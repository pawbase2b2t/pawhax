package com.pawhax.modules;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.entity.MovementType;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import com.pawhax.PawHax;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class DiagBounce extends Module {

    public enum State { ALIGNING, BOUNCING, BOOSTING, OBSTACLE_PASSING }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Target speed in blocks per second.")
        .defaultValue(110.0)
        .sliderRange(20.0, 200.0)
        .build()
    );

    public final Setting<Double> pitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("pitch")
        .description("Pitch to lock to while bouncing.")
        .defaultValue(85.0)
        .sliderRange(-90.0, 90.0)
        .build()
    );

    public final Setting<Boolean> debugMessages = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-messages")
        .description("Show debug messages in chat.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> autoFreeLook = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-free-look")
        .description("Enable Meteor's FreeLook module while DiagBounce is active.")
        .defaultValue(true)
        .build()
    );

    private static final double BOOST_ENGAGE_SPEED = 20.0;
    private static final double OBSTACLE_DISTANCE  = 6.0;
    private static final double MAX_OBSTACLE_RANGE = 16.0 * 5;

    public DiagBounce() {
        super(PawHax.CATEGORY, "diag-bounce", "High-speed diagonal elytra bounce.");
    }

    private State state = State.ALIGNING;
    private float lockedYaw;
    private Vec3d activationPos;
    private int capturedTargetY;

    private int collisionTicks;
    private boolean wasAlignedCollision;

    private Vec3d lastPos;
    private int stuckTimer;
    private int bouncingTicks;
    private int zeroSpeedTicks;
    private int lowBoostTicks;
    private boolean prevOnGround;
    private boolean wasCruising;

    private int obstaclePassingTicks;
    private int rubberBandCount;
    private int driftTicks;
    private int settleTicksRemaining;
    private int postSlipTicks;
    private int preBounceDelay;
    private int postPasserCooldown;
    private int prePasserDelay;
    private BlockPos pendingPasserGoal;
    private boolean freeLookEnabledByUs;
    private boolean baritoneLoaded;

    @Override
    public void onActivate() {
        if (mc.player == null) { toggle(); return; }
        baritoneLoaded = FabricLoader.getInstance().isModLoaded("baritone")
            || FabricLoader.getInstance().isModLoaded("baritone-meteor");
        state = State.ALIGNING;
        lockedYaw = nearestDiagonal(mc.player.getYaw());
        activationPos = mc.player.getPos();
        capturedTargetY = (int) Math.floor(mc.player.getY());
        collisionTicks = 0;
        wasAlignedCollision = false;
        lastPos = mc.player.getPos();
        stuckTimer = 0;
        bouncingTicks = 0;
        zeroSpeedTicks = 0;
        lowBoostTicks = 0;
        prevOnGround = false;
        obstaclePassingTicks = 0;
        rubberBandCount = 0;
        driftTicks = 0;
        settleTicksRemaining = 0;
        postSlipTicks = 0;
        preBounceDelay = 0;
        postPasserCooldown = 0;
        prePasserDelay = 0;
        pendingPasserGoal = null;
        wasCruising = false;

        freeLookEnabledByUs = false;
        if (autoFreeLook.get()) {
            Module freeLook = Modules.get().get("free-look");
            if (freeLook != null && !freeLook.isActive()) {
                freeLook.toggle();
                freeLookEnabledByUs = true;
            }
        }
    }

    @Override
    public void onDeactivate() {
        releaseForwardKey();
        if (baritoneLoaded && mc.world != null) {
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().currentDestination() == null) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoal(null);
            }
        }
        if (freeLookEnabledByUs) {
            Module freeLook = Modules.get().get("free-look");
            if (freeLook != null && freeLook.isActive()) freeLook.toggle();
            freeLookEnabledByUs = false;
        }
    }

    /** True when the elytra-fly mixins should be active (BOUNCING or BOOSTING). */
    public boolean isFlyEnabled() {
        return isActive() && mc.player != null && (state == State.BOUNCING || state == State.BOOSTING);
    }

    @Override
    public String getInfoString() {
        return state.name();
    }

    // --- Events ---

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerRespawnS2CPacket) {
            // Dimension change or respawn — disable cleanly to avoid crash during world transition.
            mc.execute(() -> { if (isActive()) toggle(); });
            return;
        }
        if (!(event.packet instanceof PlayerPositionLookS2CPacket)) return;
        if (state == State.OBSTACLE_PASSING) {
            mc.execute(() -> dbg("rubber-band (walking) in OBSTACLE_PASSING bps=" + String.format("%.1f", horizontalSpeedBps())));
            return;
        }
        if (state != State.BOUNCING && state != State.BOOSTING) return;
        mc.execute(() -> {
            double bps = horizontalSpeedBps();
            if (postPasserCooldown > 0) {
                dbg("rubber-band during cooldown (" + postPasserCooldown + "t left), ignoring bps=" + String.format("%.1f", bps));
                lastPos = null;
                wasCruising = false;
                lowBoostTicks = 0;
                state = State.BOUNCING;
                return;
            }
            rubberBandCount++;
            dbg("rubber-band #" + rubberBandCount + " in " + state + " bps=" + String.format("%.1f", bps));
            if (baritoneLoaded && rubberBandCount >= 6) {
                dbg("obstacle: rubber-band loop x" + rubberBandCount + ", engaging passer");
                rubberBandCount = 0;
                engageObstaclePasser();
                return;
            }
            lastPos = null;
            wasCruising = false;
            lowBoostTicks = 0;
            state = State.BOUNCING;
        });
    }

    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (mc.player == null || event.type != MovementType.SELF || state != State.BOOSTING) return;
        if (!mc.player.isOnGround() || !mc.player.isSprinting()) return;
        double speedBps = mc.player.getVelocity().multiply(1, 0, 1).length() * 20.0;
        if (speedBps >= speed.get()) return;
        ((IVec3d) event.movement).meteor$setY(0.0);
        mc.player.setVelocity(mc.player.getVelocity().x, 0.0, mc.player.getVelocity().z);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.player.getAbilities().allowFlying) return;

        switch (state) {
            case ALIGNING         -> tickAligning();
            case BOUNCING         -> tickBouncing();
            case BOOSTING         -> tickBoosting();
            case OBSTACLE_PASSING -> tickObstaclePassing();
        }

        lastPos = mc.player.getPos();
    }

    // --- State ticks ---

    private void tickAligning() {
        if (settleTicksRemaining > 0) {
            holdForwardKey(false);
            mc.player.setSprinting(false);
            settleTicksRemaining--;
            if (settleTicksRemaining == 0) dbg("align: settle done, beginning walk");
            return;
        }
        mc.player.setYaw(lockedYaw);
        mc.player.setSprinting(true);
        holdForwardKey(true);
        stuckTimer++;

        if (mc.player.horizontalCollision) {
            collisionTicks++;
            postSlipTicks = 0;
            if (collisionTicks == 1) {
                Vec3d dir = yawToDirection(lockedYaw);
                int dx = (int) Math.round(dir.x);
                int dz = (int) Math.round(dir.z);
                BlockPos here = mc.player.getBlockPos();
                dbg("align: collision t=1 at " + here
                    + " fwdX=" + mc.world.getBlockState(here.add(dx, 0, 0)).getBlock()
                    + " fwdZ=" + mc.world.getBlockState(here.add(0, 0, dz)).getBlock());
            }
            // 1 tick of contact is enough — notch protrusions only cause 1–2 tick grazes
            if (collisionTicks >= 1) wasAlignedCollision = true;
            if (wasAlignedCollision && collisionTicks > 40) {
                dbg("align: fallback stuck " + collisionTicks + "t");
                enterBouncing();
            }
        } else {
            if (wasAlignedCollision) {
                if (postSlipTicks == 0) {
                    dbg("align: slip-off at " + collisionTicks + "t, walking 10t");
                    postSlipTicks = 10;
                } else {
                    postSlipTicks--;
                    if (postSlipTicks == 0) enterBouncing();
                }
            } else {
                if (collisionTicks > 0) {
                    // Brief contact that didn't set wasAlignedCollision — log and reset
                    dbg("align: brief collision " + collisionTicks + "t then lost (stuckTimer=" + stuckTimer + ")");
                }
                collisionTicks = 0;
            }
        }

        if (!wasAlignedCollision && stuckTimer > 100) {
            dbg("align: timeout (" + stuckTimer + "t, no collision)");
            enterBouncing();
        }
    }

    private void enterBouncing() {
        holdForwardKey(false);
        // Do NOT zero velocity here — causes server desync / rubberband on the walking player.
        // The elytra bounce takes over naturally within 1–2 ticks.
        capturedTargetY = (int) Math.floor(mc.player.getY());
        lastPos = mc.player.getPos();
        stuckTimer = 0;
        bouncingTicks = 0;
        zeroSpeedTicks = 0;
        rubberBandCount = 0;
        driftTicks = 0;
        postSlipTicks = 0;
        preBounceDelay = 5;
        postPasserCooldown = 40;
        wasCruising = false;
        collisionTicks = 0;
        wasAlignedCollision = false;
        dbg("bounce start: y=" + capturedTargetY);
        state = State.BOUNCING;
    }

    private void tickBouncing() {
        mc.player.setYaw(lockedYaw);
        mc.player.setPitch(pitch.get().floatValue());
        sendStartFlyingPacket();

        if (preBounceDelay > 0) {
            preBounceDelay--;
            if (mc.player.isOnGround()) mc.player.jump();
            return;
        }

        if (mc.player.isOnGround()) {
            mc.player.jump();
        }

        double hspeed = horizontalSpeedBps();
        if (hspeed >= 40.0) wasCruising = true;

        if (hspeed >= BOOST_ENGAGE_SPEED) {
            zeroSpeedTicks = 0;
            lowBoostTicks = 0;
            bouncingTicks = 0;
            state = State.BOOSTING;
            return;
        }

        if (wasCruising) lowBoostTicks++;
        if (lowBoostTicks > 40) {
            dbg("obstacle: stuck low speed bps=" + String.format("%.1f", hspeed));
            lowBoostTicks = 0;
            engageObstaclePasser();
            return;
        }

        bouncingTicks++;
        if (hspeed < 1.0) zeroSpeedTicks++;
        else zeroSpeedTicks = 0;

        if (bouncingTicks % 20 == 0) {
            double drift = distancePointToDirection(mc.player.getPos(), yawToDirection(lockedYaw), activationPos);
            dbg("dbg bounce t=" + bouncingTicks + ": bps=" + String.format("%.1f", hspeed)
                + " drift=" + String.format("%.1f", drift) + " y=" + mc.player.getBlockPos().getY()
                + " onGround=" + mc.player.isOnGround());
        }

        if (bouncingTicks > 200) {
            dbg("obstacle: bouncing timeout " + bouncingTicks + "t");
            engageObstaclePasser();
            return;
        }
        if (zeroSpeedTicks > 40) {
            dbg("obstacle: zero speed " + zeroSpeedTicks + "t in BOUNCING");
            engageObstaclePasser();
            return;
        }

        if (postPasserCooldown > 0) postPasserCooldown--;
        else if (shouldPassObstacle()) engageObstaclePasser();
    }

    private void tickBoosting() {
        mc.player.setYaw(lockedYaw);
        mc.player.setPitch(pitch.get().floatValue());
        sendStartFlyingPacket();

        double hspeed = horizontalSpeedBps();
        boolean onGround = mc.player.isOnGround();
        Vec3d vel = mc.player.getVelocity();

        // Clear rubber-band counter only when genuinely cruising at target speed.
        // BOOST_ENGAGE_SPEED (20 bps) is too low — the rubber-band loop itself can
        // reach ~30 bps momentarily, which would reset the counter before it fires.
        if (hspeed >= speed.get() * 0.75) rubberBandCount = 0;

        // Log every ground contact and departure to understand bounce mechanics
        if (!prevOnGround && onGround) {
            dbg("dbg land: bps=" + String.format("%.1f", hspeed)
                + " vel=(" + String.format("%.2f", vel.x) + "," + String.format("%.2f", vel.y) + "," + String.format("%.2f", vel.z) + ")"
                + " y=" + String.format("%.2f", mc.player.getY()));
        }
        if (prevOnGround && !onGround) {
            dbg("dbg takeoff: bps=" + String.format("%.1f", hspeed)
                + " velY=" + String.format("%.2f", vel.y)
                + " gliding=" + mc.player.isGliding());
        }
        prevOnGround = onGround;

        if (onGround && hspeed < speed.get()) {
            mc.player.jump();
        }

        if (hspeed < 1.0) zeroSpeedTicks++;
        else zeroSpeedTicks = 0;

        if (hspeed >= 40.0) wasCruising = true;

        if (hspeed < BOOST_ENGAGE_SPEED) {
            if (wasCruising) lowBoostTicks++;
        } else {
            lowBoostTicks = 0;
        }

        if (mc.player.age % 40 == 0) {
            double drift = distancePointToDirection(mc.player.getPos(), yawToDirection(lockedYaw), activationPos);
            dbg("dbg boost: bps=" + String.format("%.1f", hspeed)
                + " drift=" + String.format("%.1f", drift) + " lowBoost=" + lowBoostTicks
                + " zero=" + zeroSpeedTicks + " y=" + mc.player.getBlockPos().getY());
        }

        if (zeroSpeedTicks > 40) {
            dbg("obstacle: zero speed " + zeroSpeedTicks + "t in BOOSTING");
            engageObstaclePasser();
            return;
        }

        if (lowBoostTicks > 20) {
            dbg("obstacle: below boost speed for " + lowBoostTicks + "t");
            engageObstaclePasser();
            return;
        }

        if (postPasserCooldown > 0) postPasserCooldown--;
        else if (shouldPassObstacle()) engageObstaclePasser();
    }

    private void tickObstaclePassing() {
        if (!baritoneLoaded) {
            state = State.ALIGNING;
            return;
        }

        // Wait for the pre-passer delay before handing off to baritone.
        if (prePasserDelay > 0) {
            prePasserDelay--;
            return;
        }

        // Dispatch the deferred goal to baritone once the delay has elapsed.
        if (pendingPasserGoal != null) {
            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(pendingPasserGoal));
            dbg("passer: baritone start at " + pendingPasserGoal);
            pendingPasserGoal = null;
            return;
        }

        obstaclePassingTicks++;
        if (obstaclePassingTicks > 600) {
            dbg("passer: timeout at " + mc.player.getBlockPos() + ", returning to align");
            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoal(null);
            holdForwardKey(false);
            stuckTimer = 0;
            zeroSpeedTicks = 0;
            lowBoostTicks = 0;
            wasCruising = false;
            collisionTicks = 0;
            wasAlignedCollision = false;
            obstaclePassingTicks = 0;
            settleTicksRemaining = 5;
            state = State.ALIGNING;
            return;
        }

        if (BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().getGoal() == null) {
            dbg("passer: done at " + mc.player.getBlockPos());
            holdForwardKey(false);
            stuckTimer = 0;
            zeroSpeedTicks = 0;
            lowBoostTicks = 0;
            wasCruising = false;
            collisionTicks = 0;
            wasAlignedCollision = false;
            obstaclePassingTicks = 0;
            settleTicksRemaining = 5;
            state = State.ALIGNING;
        }
    }

    // --- Obstacle passer ---

    private boolean shouldPassObstacle() {
        if (!baritoneLoaded) return false;
        int y = (int) mc.player.getY();
        boolean yOutOfBounds = mc.player.isOnGround() && (y < capturedTargetY - 1 || y > capturedTargetY + 4);
        double drift = distancePointToDirection(mc.player.getPos(), yawToDirection(lockedYaw), activationPos);

        if (drift > 3.0) driftTicks++;
        else driftTicks = 0;

        boolean hardDrift = drift > 7.0;
        boolean sustainedDrift = driftTicks >= 6;

        if (yOutOfBounds || hardDrift || sustainedDrift) {
            if (yOutOfBounds) dbg("obstacle: y=" + y + " target=" + capturedTargetY);
            if (hardDrift) dbg("obstacle: hard drift=" + String.format("%.1f", drift));
            else if (sustainedDrift) dbg("obstacle: sustained drift=" + String.format("%.1f", drift) + " t=" + driftTicks);
            return true;
        }
        return false;
    }

    private void engageObstaclePasser() {
        if (mc.world == null) return;
        dbg("passer: engage from " + state + " at " + mc.player.getBlockPos() + " targetY=" + capturedTargetY);
        obstaclePassingTicks = 0;
        prePasserDelay = 10;
        pendingPasserGoal = null;

        Vec3d unitYawVec = yawToDirection(lockedYaw);

        // Project player position onto the diagonal line through activationPos — fixed reference
        Vec3d travelVec = mc.player.getPos().subtract(activationPos).multiply(1, 0, 1);
        double parallelDot = travelVec.dotProduct(unitYawVec);
        Vec3d projectedPos = new Vec3d(activationPos.x, 0, activationPos.z).add(unitYawVec.multiply(parallelDot));

        // Cardinal direction offsets only — diagonal blocks (wallDx, wallDz together) cannot
        // cause horizontalCollision during diagonal walking due to axis-separated collision.
        int wallDx = (int) Math.round(unitYawVec.x);
        int wallDz = (int) Math.round(unitYawVec.z);

        BlockPos fallbackGoal = null;
        double currDistance = OBSTACLE_DISTANCE;

        while (currDistance <= MAX_OBSTACLE_RANGE) {
            Vec3d goalPos = positionInDirection(projectedPos, lockedYaw, currDistance);
            BlockPos candidate = new BlockPos((int) Math.floor(goalPos.x), capturedTargetY, (int) Math.floor(goalPos.z));
            currDistance++;

            // Unloaded chunk — stop searching, use whatever fallback we have
            if (mc.world.getBlockState(candidate).getBlock() == Blocks.VOID_AIR) break;
            if (!mc.world.getBlockState(candidate.down()).isSolidBlock(mc.world, candidate.down())) continue;
            if (!mc.world.getBlockState(candidate).isAir()) continue;
            if (!mc.world.getBlockState(candidate.up()).isAir()) continue;

            // Only check cardinal-direction walls — these are the only blocks the player
            // will physically collide with during diagonal walking
            BlockPos fwdX = candidate.add(wallDx, 0, 0);
            BlockPos fwdZ = candidate.add(0, 0, wallDz);
            boolean xSolid = mc.world.getBlockState(fwdX).isSolidBlock(mc.world, fwdX);
            boolean zSolid = mc.world.getBlockState(fwdZ).isSolidBlock(mc.world, fwdZ);

            if (xSolid || zSolid) {
                dbg("passer: notch goal=" + candidate
                    + (xSolid ? " wallX=" + mc.world.getBlockState(fwdX).getBlock() : "")
                    + (zSolid ? " wallZ=" + mc.world.getBlockState(fwdZ).getBlock() : ""));
                pendingPasserGoal = candidate;
                state = State.OBSTACLE_PASSING;
                return;
            }

            if (fallbackGoal == null) fallbackGoal = candidate;

            // Stop notch search after 24 blocks past start distance
            if (currDistance > OBSTACLE_DISTANCE + 24) break;
        }

        if (fallbackGoal == null) fallbackGoal = new BlockPos(mc.player.getBlockX(), capturedTargetY, mc.player.getBlockZ());
        dbg("passer: fallback goal=" + fallbackGoal);
        pendingPasserGoal = fallbackGoal;
        state = State.OBSTACLE_PASSING;
    }

    // --- Helpers ---

    private void dbg(String msg) {
        if (debugMessages.get()) info(msg);
    }

    private void sendStartFlyingPacket() {
        if (mc.player.networkHandler == null) return;
        mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(
            mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING
        ));
    }

    private double horizontalSpeedBps() {
        if (lastPos == null) return 0.0;
        return mc.player.getPos().subtract(lastPos).multiply(20, 0, 20).length();
    }

    private void holdForwardKey(boolean hold) {
        mc.options.forwardKey.setPressed(hold);
        Input.setKeyState(mc.options.forwardKey, hold);
    }

    private void releaseForwardKey() {
        holdForwardKey(false);
    }

    /** Snap yaw to the nearest 45-degree diagonal (45, 135, 225, 315). */
    private static float nearestDiagonal(float yaw) {
        float n = ((yaw % 360) + 360) % 360;
        return (float) ((Math.round((n - 45) / 90.0) * 90 + 45 + 360) % 360);
    }

    private static Vec3d yawToDirection(double yaw) {
        double rad = yaw * Math.PI / 180.0;
        return new Vec3d(-Math.sin(rad), 0, Math.cos(rad));
    }

    private static Vec3d positionInDirection(Vec3d pos, double yaw, double dist) {
        return pos.add(yawToDirection(yaw).multiply(dist));
    }

    private static double distancePointToDirection(Vec3d point, Vec3d direction, Vec3d start) {
        point = point.multiply(1, 0, 1);
        start = start.multiply(1, 0, 1);
        direction = direction.multiply(1, 0, 1);
        double lenSq = direction.lengthSquared();
        if (lenSq == 0) return 0;
        Vec3d diff = point.subtract(start);
        double proj = diff.dotProduct(direction) / lenSq;
        Vec3d perp = diff.subtract(direction.multiply(proj));
        return perp.length();
    }
}
