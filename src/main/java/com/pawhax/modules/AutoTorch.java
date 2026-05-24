package com.pawhax.modules;

import com.pawhax.PawHax;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class AutoTorch extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> lightThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("light-threshold")
        .description("Place a torch when block light level is at or below this value.")
        .defaultValue(0)
        .min(0)
        .sliderMax(15)
        .build()
    );

    private int placeCooldown = 0;

    public AutoTorch() {
        super(PawHax.CATEGORY, "auto-torch", "Automatically places torches when block light is too low.");
    }

    @Override
    public void onActivate() {
        placeCooldown = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (mc.currentScreen != null) return;
        if (!mc.player.isOnGround()) return;

        if (placeCooldown > 0) {
            placeCooldown--;
            return;
        }

        BlockPos feetPos = mc.player.getBlockPos();
        if (mc.world.getLightLevel(LightType.BLOCK, feetPos) > lightThreshold.get()) return;

        BlockPos surfacePos = feetPos.down();
        if (!mc.world.getBlockState(surfacePos).isSolidBlock(mc.world, surfacePos)) return;
        if (!mc.world.getBlockState(feetPos).isAir()) return;

        if (!mc.player.getMainHandStack().isOf(Items.TORCH)) {
            int torchSlot = findTorchInHotbar();
            if (torchSlot == -1) {
                int invScreenSlot = findTorchInInventory();
                if (invScreenSlot == -1) return;
                torchSlot = findEmptyHotbarSlot();
                if (torchSlot == -1) torchSlot = 8;
                var handler = mc.player.playerScreenHandler;
                mc.interactionManager.clickSlot(handler.syncId, invScreenSlot, torchSlot, SlotActionType.SWAP, mc.player);
            }
            mc.player.getInventory().selectedSlot = torchSlot;
        }

        BlockHitResult hit = new BlockHitResult(
            Vec3d.ofCenter(surfacePos).add(0, 0.5, 0),
            Direction.UP,
            surfacePos,
            false
        );
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);

        placeCooldown = 10;
    }

    private int findTorchInHotbar() {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(Items.TORCH)) return i;
        }
        return -1;
    }

    // Returns the PlayerScreenHandler slot index (9-35) of the first torch in the main inventory.
    private int findTorchInInventory() {
        var handler = mc.player.playerScreenHandler;
        for (int screenSlot = 9; screenSlot <= 35; screenSlot++) {
            if (handler.getSlot(screenSlot).getStack().isOf(Items.TORCH)) return screenSlot;
        }
        return -1;
    }

    private int findEmptyHotbarSlot() {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isEmpty()) return i;
        }
        return -1;
    }
}
