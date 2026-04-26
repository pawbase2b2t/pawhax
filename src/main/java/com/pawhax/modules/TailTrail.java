package com.pawhax.modules;

import com.pawhax.PawHax;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.particle.ParticleType;
import net.minecraft.particle.SimpleParticleType;
import net.minecraft.particle.ParticleTypes;

import java.util.List;
import java.util.Random;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/*
for my pookie wrenne :3
*/

public class TailTrail extends Module {

    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgPosition = settings.createGroup("Position");

    // ── General ───────────────────────────────────────────────────────────────

    private final Setting<List<ParticleType<?>>> particles = sgGeneral.add(new ParticleTypeListSetting.Builder()
        .name("particles")
        .description("particles to include in the trail")
        .defaultValue(ParticleTypes.HEART)
        .build()
    );

    private final Setting<Integer> count = sgGeneral.add(new IntSetting.Builder()
        .name("count")
        .description("particles per burst")
        .defaultValue(3).min(1).sliderMax(10)
        .build()
    );

    private final Setting<Integer> spawnRate = sgGeneral.add(new IntSetting.Builder()
        .name("spawn-rate")
        .description("ticks between bursts, lower is more frequent")
        .defaultValue(2).min(1).sliderMax(20)
        .build()
    );

    private final Setting<Boolean> onlyWhenMoving = sgGeneral.add(new BoolSetting.Builder()
        .name("only-when-moving")
        .description("only trail particles when youre actually moving")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> upwardVelocity = sgGeneral.add(new IntSetting.Builder()
        .name("upward-velocity")
        .description("how much particles drift upward")
        .defaultValue(5).min(0).sliderMax(30)
        .build()
    );

    private final Setting<Integer> randomVelocity = sgGeneral.add(new IntSetting.Builder()
        .name("random-velocity")
        .description("random scatter on each particle")
        .defaultValue(5).min(0).sliderMax(30)
        .build()
    );

    // ── Position ──────────────────────────────────────────────────────────────

    private final Setting<Integer> heightOffset = sgPosition.add(new IntSetting.Builder()
        .name("height-offset")
        .description("vertical spawn offset from your feet")
        .defaultValue(10).min(-100).sliderMin(-100).sliderMax(250)
        .build()
    );

    private final Setting<Integer> spread = sgPosition.add(new IntSetting.Builder()
        .name("spread")
        .description("how far from your position particles can spawn")
        .defaultValue(20).min(0).sliderMax(150)
        .build()
    );

    // ── State ─────────────────────────────────────────────────────────────────

    private final Random rng = new Random();
    private int    tickCounter = 0;
    private double prevX = Double.NaN, prevZ = Double.NaN;

    public TailTrail() {
        super(PawHax.CATEGORY, "TailTrail", "leaves a cute particle trail behind you meow");
    }

    @Override
    public void onActivate() {
        prevX = Double.NaN;
        prevZ = Double.NaN;
        tickCounter = 0;
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        double curX = mc.player.getX(), curZ = mc.player.getZ();
        boolean isMoving = !Double.isNaN(prevX) && (curX != prevX || curZ != prevZ);
        prevX = curX;
        prevZ = curZ;

        if (++tickCounter < spawnRate.get()) return;
        tickCounter = 0;

        if (onlyWhenMoving.get() && !isMoving) return;

        List<ParticleType<?>> enabled = particles.get();
        if (enabled.isEmpty()) return;

        double x   = curX;
        double y   = mc.player.getY() + heightOffset.get() / 100.0;
        double z   = curZ;
        double spr = spread.get() / 100.0;
        double upV = upwardVelocity.get() / 100.0;
        double rnV = randomVelocity.get() / 100.0;

        int n = count.get();
        for (int i = 0; i < n; i++) {
            ParticleType<?> chosen = enabled.get(rng.nextInt(enabled.size()));
            if (!(chosen instanceof SimpleParticleType simple)) continue;
            double ox = (rng.nextDouble() - 0.5) * 2.0 * spr;
            double oy = (rng.nextDouble() - 0.5) * 2.0 * spr;
            double oz = (rng.nextDouble() - 0.5) * 2.0 * spr;
            double vx = (rng.nextDouble() - 0.5) * rnV;
            double vy = upV + (rng.nextDouble() - 0.5) * rnV * 0.5;
            double vz = (rng.nextDouble() - 0.5) * rnV;
            mc.world.addParticle(simple, x + ox, y + oy, z + oz, vx, vy, vz);
        }
    }
}
