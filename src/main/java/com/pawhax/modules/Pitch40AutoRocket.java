package com.pawhax.modules;

import com.pawhax.PawHax;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

public class Pitch40AutoRocket extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> fireworkCooldownTicks = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown-ticks")
        .description("Cooldown after using a firework in ticks.")
        .defaultValue(10)
        .sliderRange(0, 100)
        .build()
    );

    public Pitch40AutoRocket() {
        super(PawHax.CATEGORY, "pitch40-auto-rocket", "Automatically use a rocket when you start falling during a pitch40 climb.");
    }

    private final Module elytraFly = Modules.get().get(ElytraFly.class);
    @SuppressWarnings("unchecked")
    private final Setting<ElytraFlightModes> elytraFlyMode = (Setting<ElytraFlightModes>) elytraFly.settings.get("mode");

    private ElytraFlightModes oldMode;
    private int fireworkCooldown = 0;

    @Override
    public void onActivate() {
        fireworkCooldown = 0;
        if (!elytraFly.isActive()) elytraFly.toggle();
        oldMode = elytraFlyMode.get();
        elytraFlyMode.set(ElytraFlightModes.Pitch40);
    }

    @Override
    public void onDeactivate() {
        if (elytraFly.isActive()) elytraFly.toggle();
        elytraFlyMode.set(oldMode);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!elytraFly.isActive() || elytraFlyMode.get() != ElytraFlightModes.Pitch40) return;
        if (mc.player == null) return;

        if (fireworkCooldown > 0) {
            fireworkCooldown--;
            return;
        }

        // Fire a rocket when the player drops below the pitch40 lower bound
        if (mc.player.getY() < (double) elytraFly.settings.get("pitch40-lower-bounds").get() - 5) {
            FindItemResult itemResult = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
            if (!itemResult.found()) return;

            InvUtils.swap(itemResult.slot(), true);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            mc.player.swingHand(Hand.MAIN_HAND);
            InvUtils.swapBack();

            fireworkCooldown = fireworkCooldownTicks.get();
        }
    }
}
