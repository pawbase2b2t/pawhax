package com.pawhax.mixin;

import com.pawhax.modules.DiagBounce;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

import static meteordevelopment.meteorclient.MeteorClient.mc;

@Mixin(Entity.class)
public class DiagBounceEntityMixin {

    @Shadow
    protected UUID uuid;

    @Unique
    private DiagBounce diagBounce = null;

    @Unique
    private DiagBounce getModule() {
        if (diagBounce == null) diagBounce = Modules.get().get(DiagBounce.class);
        return diagBounce;
    }

    @Inject(at = @At("HEAD"), method = "getPose()Lnet/minecraft/entity/EntityPose;", cancellable = true)
    private void getPose(CallbackInfoReturnable<EntityPose> cir) {
        DiagBounce m = getModule();
        if (m != null && m.isFlyEnabled() && mc.player != null && this.uuid.equals(mc.player.getUuid())) {
            cir.setReturnValue(EntityPose.STANDING);
        }
    }

    @Inject(at = @At("HEAD"), method = "isSprinting()Z", cancellable = true)
    private void isSprinting(CallbackInfoReturnable<Boolean> cir) {
        DiagBounce m = getModule();
        if (m != null && m.isFlyEnabled() && mc.player != null && this.uuid.equals(mc.player.getUuid())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(at = @At("HEAD"), method = "pushAwayFrom", cancellable = true)
    private void pushAwayFrom(Entity entity, CallbackInfo ci) {
        DiagBounce m = getModule();
        if (mc.player != null && this.uuid.equals(mc.player.getUuid()) && m != null && m.isFlyEnabled()
                && !entity.getUuid().equals(this.uuid)) {
            ci.cancel();
        }
    }
}
