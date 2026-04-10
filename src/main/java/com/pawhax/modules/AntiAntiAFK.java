package com.pawhax.modules;

import com.pawhax.PawHax;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.util.Hand;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class AntiAntiAFK extends Module {
    private static final long AFK_THRESHOLD_MS = 15 * 60_000L;

    private double lastX, lastY, lastZ;
    private long lastMovedMs;
    private long lastSwingMs;

    public AntiAntiAFK() {
        super(PawHax.CATEGORY, "AntiAntiAFK", "you'll never get kicked ever again :3");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;
        lastX = mc.player.getX();
        lastY = mc.player.getY();
        lastZ = mc.player.getZ();
        lastMovedMs = System.currentTimeMillis();
        lastSwingMs = System.currentTimeMillis();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();

        if (x != lastX || y != lastY || z != lastZ) {
            lastX = x;
            lastY = y;
            lastZ = z;
            lastMovedMs = System.currentTimeMillis();
            lastSwingMs = System.currentTimeMillis();
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastMovedMs >= AFK_THRESHOLD_MS && now - lastSwingMs >= AFK_THRESHOLD_MS) {
            mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
            lastSwingMs = now;
        }
    }
}
