package com.pawhax.modules;

import com.pawhax.PawHax;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

import java.util.ArrayDeque;
import java.util.Queue;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class AntiAntiAFK extends Module {
    private final SettingGroup sgIdleActions = settings.createGroup("idle actions");
    private final SettingGroup sgAutoLog     = settings.createGroup("auto logout");

    // IDLE ACTIONS ----------------------------------------------------------------------

    private final Setting<Integer> idleTimeout = sgIdleActions.add(new IntSetting.Builder()
        .name("idle-timeout")
        .description("how long since you last moved yourself before idle actions trigger (seconds)")
        .defaultValue(300).sliderRange(60,600)
        .build()
    );

    private final Setting<Boolean> spin = sgIdleActions.add(new BoolSetting.Builder()
        .name("spin")
        .description("spins when idle")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> spinSpeed = sgIdleActions.add(new IntSetting.Builder()
        .name("spin-speed")
        .description("degrees to rotate per tick")
        .defaultValue(15).sliderRange(10, 30)
        .build()
    );

    private final Setting<Boolean> chestSwap = sgIdleActions.add(new BoolSetting.Builder()
        .name("chest-swap")
        .description("equips a chestplate and swaps back when you move")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> mainhandTotem = sgIdleActions.add(new BoolSetting.Builder()
        .name("mainhand-totem")
        .description("puts a totem in your mainhand (zuden mode)")
        .defaultValue(true)
        .build()
    );

//    private final Setting<Boolean> triggerNow = sgIdleActions.add(new BoolSetting.Builder()
//       .name("trigger-now")
//       .description("instantly triggers idle actions for testing")
//       .defaultValue(false)
//       .build()
//    );

    // AUTO LOG --------------------------------------------------------------------------

    // MAKE AUTORECONNECT TOGGLE BOTH METEOR AND RUSHER PLS

    private final Setting<Boolean> logoutOnTotemPop = sgAutoLog.add(new BoolSetting.Builder()
        .name("totem-pop")
        .description("logs out if a totem pops while youre afk")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> logoutOnPearl = sgAutoLog.add(new BoolSetting.Builder()
        .name("on-pearl")
        .description("logs out if you get pearled somewhere while afk")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> logoutOnDistance = sgAutoLog.add(new BoolSetting.Builder()
        .name("moved-too-far")
        .description("logs out if you get moved more than a set distance from your afk spot")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> logoutDistance = sgAutoLog.add(new IntSetting.Builder()
        .name("logout-distance")
        .description("blocks you can be moved before logging out")
        .defaultValue(5).min(1).sliderMax(20)
        .visible(logoutOnDistance::get)
        .build()
    );

    private final Setting<Boolean> disableAutoReconnect = sgAutoLog.add(new BoolSetting.Builder()
        .name("disable-autoreconnect")
        .description("disable meteor autorecon if yea")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> disableExtAutoReconnect = sgAutoLog.add(new BoolSetting.Builder()
        .name("disable-external-autoreconnect")
        .description("disable rusher or mio or whatever")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> externalAutoReconnectCommand = sgAutoLog.add(new StringSetting.Builder()
        .name("ext-reconnect-cmd")
        .description("command do turn off ur autoreconnect on other client")
        .defaultValue("*toggle autoreconnect false")
        .visible(disableExtAutoReconnect::get)
        .build()
    );

    private static final int  TOTEM_STATUS_ID   = 35;
    private static final long SWING_INTERVAL_MS = 15 * 60_000L;

    // STATES --------------------------------------------------------------------------

    private double lastX, lastY, lastZ;
    private long   lastSwingMs, lastPlayerMovedMs, activateTimeMs;
    private float  serverYaw;
    private boolean wasIdle, idlePositionSaved;
    private double  afkStartX, afkStartY, afkStartZ;

    private int     chestSwapSlot   = -1;
    private boolean totemMoved;
    private int     totemSourceSlot = -1;
    private int     totemHotbarSlot = -1;
    private int     prevSelectedSlot;

    private final Queue<Runnable> pendingClicks = new ArrayDeque<>();
    private boolean pearlPending;
    private double  prePacketX, prePacketY, prePacketZ;
    private boolean autoSwingPending;

    public AntiAntiAFK() {
        // fuck my stupid chud life this module is so complex for what it is
        super(PawHax.CATEGORY, "AntiAntiAFK", "youll never get kicked ever again :3");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;
        lastX = mc.player.getX();
        lastY = mc.player.getY();
        lastZ = mc.player.getZ();
        afkStartX = lastX; afkStartY = lastY; afkStartZ = lastZ;
        lastSwingMs = lastPlayerMovedMs = activateTimeMs = System.currentTimeMillis();
        serverYaw = mc.player.getYaw();
        wasIdle = false;
        idlePositionSaved = false;
        chestSwapSlot = -1;
        totemMoved = false; totemSourceSlot = -1; totemHotbarSlot = -1; prevSelectedSlot = 0;
        pendingClicks.clear();
        pearlPending = false;
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null && wasIdle) {
            float yaw = mc.player.getYaw();
            mc.player.bodyYaw = yaw;
            mc.player.headYaw = yaw;
            if (chestSwap.get() && chestSwapSlot != -1) queueChestSwapBack();
            if (mainhandTotem.get() && totemMoved)      queueTotemRestore();
        }
        while (!pendingClicks.isEmpty()) pendingClicks.poll().run();
        wasIdle = false;
        pearlPending = false;
    }

    // TICK -----------------------------------------------------------------------

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        if (!pendingClicks.isEmpty()) pendingClicks.poll().run();

        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();

        if (pearlPending) {
            pearlPending = false;
            double pdx = x - prePacketX, pdy = y - prePacketY, pdz = z - prePacketZ;
            int min = 3;
            if (pdx * pdx + pdy * pdy + pdz * pdz >= min * min) doLogout("[AntiAntiAFK] autolog: player pearled somewhere");
        }

        if (x != lastX || y != lastY || z != lastZ) {
            lastX = x; lastY = y; lastZ = z;
            lastSwingMs = System.currentTimeMillis();
        }

        // anything 2b thinks is "activity" i suppose
        boolean playerActive =
            mc.options.forwardKey.isPressed() || mc.options.backKey.isPressed()  ||
            mc.options.leftKey.isPressed()    || mc.options.rightKey.isPressed() ||
            mc.options.jumpKey.isPressed()    || mc.options.sneakKey.isPressed() ||
            mc.player.isUsingItem() ||
            (mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA) && !mc.player.isOnGround());

        if (playerActive) lastPlayerMovedMs = System.currentTimeMillis();

        long now = System.currentTimeMillis();
        boolean idle = now - lastPlayerMovedMs >= idleTimeout.get() * 1000L;

        // move everythinbg back
        if (wasIdle && !idle) {
            float yaw = mc.player.getYaw();
            mc.player.bodyYaw = yaw;
            mc.player.headYaw = yaw;
            if (chestSwap.get() && chestSwapSlot != -1) queueChestSwapBack();
            if (mainhandTotem.get() && totemMoved)      queueTotemRestore();
            wasIdle = false;
            idlePositionSaved = false;
            serverYaw = mc.player.getYaw();
        }

        // save pos for the move autolog and shi
        if (idle && !idlePositionSaved) {
            afkStartX = x; afkStartY = y; afkStartZ = z;
            idlePositionSaved = true;
        }

        // all important swing packets of curtis life giving
        if (now - lastSwingMs >= SWING_INTERVAL_MS) {
            autoSwingPending = true;
            mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
            lastSwingMs = now;
        }

        if (idle) {
            if (!wasIdle) {
                if (chestSwap.get())     doChestSwap();
                if (mainhandTotem.get()) doTotemEquip();
            }
            wasIdle = true;

            if (spin.get()) {
                // HOW HARD IS IT TO MAKE IT SMOOTH TO SPING OMGFG
                serverYaw = (serverYaw + spinSpeed.get()) % 360f;
                mc.player.prevBodyYaw = serverYaw - spinSpeed.get();
                mc.player.prevHeadYaw = serverYaw - spinSpeed.get();
                mc.player.bodyYaw = serverYaw;
                mc.player.headYaw = serverYaw;
                Rotations.rotate(serverYaw, 0, -15);
            }
        }

        if (idle && logoutOnDistance.get()) {
            double dx = x - afkStartX, dy = y - afkStartY, dz = z - afkStartZ;
            int t = logoutDistance.get();
            if (dx * dx + dy * dy + dz * dz > t * t) doLogout("[AntiAntiAFK] autolog: player moved too far from afk spot");
        }
    }

    // packet events --------------------------------------------------------------------------------------

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null || mc.world == null) return;
        if (System.currentTimeMillis() - activateTimeMs < 5000) return;

        // check for totems and tthat
        if (event.packet instanceof EntityStatusS2CPacket packet) {
            if (packet.getStatus() == TOTEM_STATUS_ID && packet.getEntity(mc.world) == mc.player) {
                long idle = System.currentTimeMillis() - lastPlayerMovedMs;
                if (logoutOnTotemPop.get() && idle >= idleTimeout.get() * 1000L) {
                    doLogout("[AntiAntiAFK] autolog: player totem popped while afk");
                }
            }
        }

        if (event.packet instanceof PlayerPositionLookS2CPacket && logoutOnPearl.get()) {
            long idle = System.currentTimeMillis() - lastPlayerMovedMs;
            if (idle >= idleTimeout.get() * 1000L) {
                prePacketX = mc.player.getX();
                prePacketY = mc.player.getY();
                prePacketZ = mc.player.getZ();
                pearlPending = true;
            }
        }
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (!(event.packet instanceof HandSwingC2SPacket)) return;
        if (autoSwingPending) { autoSwingPending = false; return; }
        lastPlayerMovedMs = System.currentTimeMillis();
    }

    // FUNCTIONS ---------------------------------------------------------------------------------

    private void doLogout(String reason) {
        if (disableAutoReconnect.get()) {
            AutoReconnect autoReconnect = Modules.get().get(AutoReconnect.class);
            if (autoReconnect.isActive()) autoReconnect.toggle();
        }
        if (disableExtAutoReconnect.get()) {
            String cmd = externalAutoReconnectCommand.get();
            if (!cmd.isBlank()) {
                mc.player.networkHandler.sendChatMessage(cmd);
            }
        }
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().getConnection().disconnect(Text.literal(reason));
        }
    }

    private void doChestSwap() {
        var handler = mc.player.playerScreenHandler;
        for (int i = 9; i <= 44; i++) {
            var stack = handler.getSlot(i).getStack();
            var eq = stack.get(DataComponentTypes.EQUIPPABLE);
            if (eq != null && eq.slot() == EquipmentSlot.CHEST && stack.getItem() != Items.ELYTRA) {
                mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, mc.player);
                mc.interactionManager.clickSlot(handler.syncId, 6, 0, SlotActionType.PICKUP, mc.player);
                mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, mc.player);
                chestSwapSlot = i;
                return;
            }
        }
    }

    private void queueChestSwapBack() {
        var handler = mc.player.playerScreenHandler;
        final int syncId = handler.syncId;
        final int slot   = chestSwapSlot;
        chestSwapSlot = -1;
        pendingClicks.add(() -> mc.interactionManager.clickSlot(syncId, slot, 0, SlotActionType.PICKUP, mc.player));
        pendingClicks.add(() -> mc.interactionManager.clickSlot(syncId, 6,    0, SlotActionType.PICKUP, mc.player));
        pendingClicks.add(() -> mc.interactionManager.clickSlot(syncId, slot, 0, SlotActionType.PICKUP, mc.player));
    }

    private void queueTotemRestore() {
        if (totemSourceSlot != -1) {
            var handler   = mc.player.playerScreenHandler;
            final int syncId = handler.syncId;
            final int srcSlot = totemSourceSlot;
            final int hotSlot = totemHotbarSlot;
            pendingClicks.add(() -> mc.interactionManager.clickSlot(syncId, srcSlot, hotSlot, SlotActionType.SWAP, mc.player));
        }
        final int prev = prevSelectedSlot;
        pendingClicks.add(() -> mc.player.getInventory().selectedSlot = prev);
        totemMoved = false; totemSourceSlot = -1; totemHotbarSlot = -1;
    }

    private void doTotemEquip() {
        var handler = mc.player.playerScreenHandler;
        var inv = mc.player.getInventory();
        if (inv.getMainHandStack().getItem() == Items.TOTEM_OF_UNDYING) return;

        for (int i = 36; i <= 44; i++) {
            if (handler.getSlot(i).getStack().getItem() == Items.TOTEM_OF_UNDYING) {
                prevSelectedSlot = inv.selectedSlot;
                inv.selectedSlot = i - 36;
                totemMoved = true; totemSourceSlot = -1;
                return;
            }
        }
        // fucking
        int totemSlot = -1;
        for (int i = 9; i <= 35; i++) {
            if (handler.getSlot(i).getStack().getItem() == Items.TOTEM_OF_UNDYING) {
                totemSlot = i; break;
            }
        }
        if (totemSlot == -1) return;

        int hotbarTarget = 8;
        for (int i = 36; i <= 44; i++) {
            if (handler.getSlot(i).getStack().isEmpty()) {
                hotbarTarget = i - 36; break;
            }
        }

        mc.interactionManager.clickSlot(handler.syncId, totemSlot, hotbarTarget, SlotActionType.SWAP, mc.player);
        prevSelectedSlot = inv.selectedSlot;
        inv.selectedSlot = hotbarTarget;
        totemMoved = true; totemSourceSlot = totemSlot; totemHotbarSlot = hotbarTarget;
    }
}
