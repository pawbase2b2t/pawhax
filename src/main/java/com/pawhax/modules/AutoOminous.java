package com.pawhax.modules;

import com.pawhax.PawHax;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;

public class AutoOminous extends Module {

    private int swapScreenSlot = -1;
    private int swapHotbarSlot = -1;
    private boolean holdingUseKey = false;

    public AutoOminous() {
        super(PawHax.CATEGORY, "auto-ominous", "Automatically drinks Ominous Bottles to maintain Bad Omen.");
    }

    @Override
    public void onDeactivate() {
        releaseUseKey();
        restoreSlot();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (mc.player.isUsingItem()) {
            if (holdingUseKey && mc.player.hasStatusEffect(StatusEffects.BAD_OMEN)) {
                // First bottle finished and the game auto-started the next one — stop it
                releaseUseKey();
                mc.player.stopUsingItem();
                restoreSlot();
            } else if (holdingUseKey) {
                mc.options.useKey.setPressed(true);
            }
            return;
        }

        if (holdingUseKey) {
            releaseUseKey();
            restoreSlot();
        }

        if (mc.player.hasStatusEffect(StatusEffects.BAD_OMEN)) return;

        FindItemResult bottle = InvUtils.find(Items.OMINOUS_BOTTLE);
        if (!bottle.found()) return;

        int invSlot = bottle.slot();
        // PlayerInventory slots 0-8 = hotbar → screen handler slots 36-44
        // PlayerInventory slots 9-35 = main inventory → screen handler slots 9-35
        int screenSlot = invSlot <= 8 ? invSlot + 36 : invSlot;
        int currentHotbar = mc.player.getInventory().getSelectedSlot();

        mc.interactionManager.clickSlot(
            mc.player.playerScreenHandler.syncId,
            screenSlot, currentHotbar, SlotActionType.SWAP, mc.player
        );
        swapScreenSlot = screenSlot;
        swapHotbarSlot = currentHotbar;

        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        mc.options.useKey.setPressed(true);
        holdingUseKey = true;
    }

    private void releaseUseKey() {
        mc.options.useKey.setPressed(false);
        holdingUseKey = false;
    }

    private void restoreSlot() {
        if (swapScreenSlot == -1 || mc.player == null) return;
        mc.interactionManager.clickSlot(
            mc.player.playerScreenHandler.syncId,
            swapScreenSlot, swapHotbarSlot, SlotActionType.SWAP, mc.player
        );
        swapScreenSlot = -1;
        swapHotbarSlot = -1;
    }
}
