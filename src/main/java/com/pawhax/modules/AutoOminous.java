package com.pawhax.modules;

import com.pawhax.PawHax;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

public class AutoOminous extends Module {

    private boolean swapped = false;

    public AutoOminous() {
        super(PawHax.CATEGORY, "auto-ominous", "Automatically drinks Ominous Bottles to maintain Bad Omen.");
    }

    @Override
    public void onDeactivate() {
        if (swapped) {
            InvUtils.swapBack();
            swapped = false;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (mc.player.isUsingItem()) return;

        if (swapped) {
            InvUtils.swapBack();
            swapped = false;
        }

        if (mc.player.hasStatusEffect(StatusEffects.BAD_OMEN)) return;

        FindItemResult bottle = InvUtils.find(Items.OMINOUS_BOTTLE);
        if (!bottle.found()) return;

        InvUtils.swap(bottle.slot(), true);
        swapped = true;
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
    }
}
