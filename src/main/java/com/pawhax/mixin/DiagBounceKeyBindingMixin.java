package com.pawhax.mixin;

import com.pawhax.modules.DiagBounce;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KeyBinding.class)
public abstract class DiagBounceKeyBindingMixin {

    @Unique
    private DiagBounce diagBounce = null;

    @Inject(at = @At("RETURN"), method = "isPressed", cancellable = true)
    public void isPressed(CallbackInfoReturnable<Boolean> cir) {
        // Lazy init to avoid crash before Meteor is fully loaded
        if (diagBounce == null) diagBounce = Modules.get().get(DiagBounce.class);
        if (diagBounce != null && diagBounce.isFlyEnabled()
                && ((KeyBinding)(Object)this).getId().equals("key.forward")) {
            cir.setReturnValue(true);
        }
    }
}
