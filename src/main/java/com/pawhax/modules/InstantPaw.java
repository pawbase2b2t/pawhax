package com.pawhax.modules;

import com.pawhax.PawHax;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/* im going to die */

public class InstantPaw extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> targetPlayer = sgGeneral.add(new StringSetting.Builder()
        .name("target-player")
        .description("username of the player to pull")
        .defaultValue("AvidMurrsuiter")
        .build()
    );

    private BlockPos trapdoorPos = null;
    private boolean wasOnline = false;

    public InstantPaw() {
        super(PawHax.CATEGORY, "InstaPaw", "watches for a player joining and instantly flips a trapdoor");
    }

    @Override
    public void onActivate() {
        trapdoorPos = null;

        String target = targetPlayer.get().trim();
        if (target.isEmpty()) {
            info("set player first");
            mc.execute(this::toggle);
            return;
        }

        if (!(mc.crosshairTarget instanceof BlockHitResult bhr)
            || mc.world == null
            || !(mc.world.getBlockState(bhr.getBlockPos()).getBlock() instanceof TrapdoorBlock)) {
            info("look at trapdoor before enabling");
            mc.execute(this::toggle);
            return;
        }

        trapdoorPos = bhr.getBlockPos();
        wasOnline = isTargetOnline();
        info("waiting for (highlight)%s(default) to join...", target);
    }

    @Override
    public void onDeactivate() {
        trapdoorPos = null;
        wasOnline = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || trapdoorPos == null) return;

        boolean nowOnline = isTargetOnline();

        if (nowOnline && !wasOnline) {
            mc.interactionManager.interactBlock(
                mc.player,
                Hand.MAIN_HAND,
                new BlockHitResult(Vec3d.ofCenter(trapdoorPos), Direction.UP, trapdoorPos, false)
            );
            toggle();
        }

        wasOnline = nowOnline;
    }

    private boolean isTargetOnline() {
        if (mc.player == null || mc.player.networkHandler == null) return false;
        String target = targetPlayer.get().trim();
        if (target.isEmpty()) return false;
        return mc.player.networkHandler.getPlayerList().stream()
            .anyMatch(e -> e.getProfile().getName().equalsIgnoreCase(target));
    }
}
