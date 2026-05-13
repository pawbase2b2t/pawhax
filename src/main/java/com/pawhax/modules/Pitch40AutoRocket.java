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

    private final Module elytraFly;
    private final Setting<ElytraFlightModes> elytraFlyMode;

    private ElytraFlightModes oldMode = ElytraFlightModes.Pitch40;
    private boolean elytraFlyWasActive = false;
    private int fireworkCooldown = 0;

    @SuppressWarnings("unchecked")
    public Pitch40AutoRocket() {
        super(PawHax.CATEGORY, "pitch40-auto-rocket", "Automatically use a rocket when you start falling during a pitch40 climb.");
        Module fly = Modules.get().get(ElytraFly.class);
        this.elytraFly = fly;
        this.elytraFlyMode = fly != null ? (Setting<ElytraFlightModes>) fly.settings.get("mode") : null;
    }

    @Override
    public void onActivate() {
        fireworkCooldown = 0;
        if (elytraFly == null || elytraFlyMode == null) {
            toggle();
            return;
        }
        elytraFlyWasActive = elytraFly.isActive();
        oldMode = elytraFlyMode.get();
        if (!elytraFly.isActive()) elytraFly.toggle();
        elytraFlyMode.set(ElytraFlightModes.Pitch40);
    }

    @Override
    public void onDeactivate() {
        if (elytraFly == null || elytraFlyMode == null) return;
        elytraFlyMode.set(oldMode);
        if (!elytraFlyWasActive && elytraFly.isActive()) elytraFly.toggle();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (elytraFly == null || elytraFlyMode == null) return;
        if (!elytraFly.isActive() || elytraFlyMode.get() != ElytraFlightModes.Pitch40) return;
        if (mc.player == null) return;

        if (fireworkCooldown > 0) {
            fireworkCooldown--;
            return;
        }

        Setting<?> lowerBoundsSetting = elytraFly.settings.get("pitch40-lower-bounds");
        if (lowerBoundsSetting == null) return;
        Object lowerBoundsValue = lowerBoundsSetting.get();
        if (!(lowerBoundsValue instanceof Number)) return;
        double lowerBounds = ((Number) lowerBoundsValue).doubleValue();

        if (mc.player.getY() < lowerBounds - 5) {
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
