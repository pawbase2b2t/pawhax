package com.pawhax.mixin;

import com.pawhax.modules.DiagBounce;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KeyBinding.class)
public abstract class DiagBounceKeyBindingMixin {

    @Final
    @Shadow
    private String translationKey;

    @Unique
    private DiagBounce diagBounce = null;

    @Inject(at = @At("RETURN"), method = "isPressed", cancellable = true)
    public void isPressed(CallbackInfoReturnable<Boolean> cir) {
        if (diagBounce == null) {
            Modules modules = Modules.get();
            if (modules == null) return;
            diagBounce = modules.get(DiagBounce.class);
        }
        if (diagBounce != null && diagBounce.isFlyEnabled() && translationKey.equals("key.forward")) {
            cir.setReturnValue(true);
        }
    }
}
