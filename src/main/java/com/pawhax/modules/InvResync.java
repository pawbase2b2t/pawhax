package com.pawhax.modules;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.screen.slot.SlotActionType;

import com.pawhax.PawHax;

public class InvResync extends Module {

    private static final double PENDING_TIMEOUT_SECONDS = 5.0;
    private static final double RETRY_COOLDOWN_SECONDS = 2.0;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> resyncInterval = sgGeneral.add(new DoubleSetting.Builder()
            .name("resync-interval")
            .description("Maximum seconds allowed between full inventory syncs from the server before forcing one.")
            .defaultValue(3.0)
            .min(0.1)
            .max(30.0)
            .sliderMin(2.0)
            .sliderMax(30.0)
            .build()
    );

    private long lastFullSyncNanos = 0L;
    private long pendingResyncSentNanos = 0L;
    private long retryCooldownUntilNanos = 0L;

    public InvResync() {
        super(PawHax.CATEGORY, "inv-resync",
                "Forces a full inventory resync if the server hasn't sent one within the configured interval. DO NOT USE IF YOU DON'T KNOW WHAT YOU ARE DOING. CAN CAUSE MORE ISSUES THAN IT SOLVES.");
    }

    @Override
    public void onActivate() {
        lastFullSyncNanos = System.nanoTime();
        pendingResyncSentNanos = 0L;
        retryCooldownUntilNanos = 0L;
    }

    @Override
    public void onDeactivate() {
        pendingResyncSentNanos = 0L;
        retryCooldownUntilNanos = 0L;
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (event.packet instanceof InventoryS2CPacket packet && packet.getSyncId() == 0) {
            lastFullSyncNanos = System.nanoTime();
            pendingResyncSentNanos = 0L;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (mc.player.networkHandler == null) return;
        if (mc.player.currentScreenHandler.syncId != 0) return;

        long now = System.nanoTime();

        if (pendingResyncSentNanos != 0L) {
            double pendingSeconds = (now - pendingResyncSentNanos) / 1_000_000_000.0;
            if (pendingSeconds >= PENDING_TIMEOUT_SECONDS) {
                pendingResyncSentNanos = 0L;
                lastFullSyncNanos = now;
                retryCooldownUntilNanos = now + (long)(RETRY_COOLDOWN_SECONDS * 1_000_000_000L);
            }
            return;
        }

        if (now < retryCooldownUntilNanos) return;

        double timeSinceFullSync = (now - lastFullSyncNanos) / 1_000_000_000.0;
        if (timeSinceFullSync < resyncInterval.get()) return;

        int clientRev = mc.player.currentScreenHandler.getRevision();
        int forcedRev = clientRev + 5;

        mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                0,
                forcedRev,
                0,
                0,
                SlotActionType.PICKUP,
                ItemStack.EMPTY,
                new Int2ObjectOpenHashMap<>()
        ));

        pendingResyncSentNanos = now;
    }
}