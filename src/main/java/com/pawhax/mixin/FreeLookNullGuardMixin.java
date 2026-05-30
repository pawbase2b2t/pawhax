package com.pawhax.mixin;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.render.FreeLook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static meteordevelopment.meteorclient.MeteorClient.mc;

@Mixin(value = FreeLook.class, remap = false)
public class FreeLookNullGuardMixin {

    @Inject(at = @At("HEAD"), method = "onTick", cancellable = true)
    private void guardNullPlayer(TickEvent.Pre event, CallbackInfo ci) {
        if (mc.player == null) ci.cancel();
    }
}
