package com.pawhax.mixin;

import com.pawhax.modules.DiagBounce;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static meteordevelopment.meteorclient.MeteorClient.mc;

@Mixin(LivingEntity.class)
public abstract class DiagBounceLivingEntityMixin extends Entity {

    @Shadow
    private int jumpingCooldown;

    public DiagBounceLivingEntityMixin(EntityType<?> type, World world) {
        super(type, world);
    }

    @Unique
    private DiagBounce diagBounce = null;

    @Unique
    private DiagBounce getModule() {
        if (diagBounce == null) diagBounce = Modules.get().get(DiagBounce.class);
        return diagBounce;
    }

    @Inject(at = @At("HEAD"), method = "tickMovement()V")
    private void tickMovement(CallbackInfo ci) {
        DiagBounce m = getModule();
        if (mc.player != null && (Object)this == mc.player && m != null && m.isFlyEnabled()) {
            this.jumpingCooldown = 0;
        }
    }

    @Inject(at = @At("HEAD"), method = "isGliding", cancellable = true)
    private void isGliding(CallbackInfoReturnable<Boolean> cir) {
        DiagBounce m = getModule();
        if (mc.player != null && (Object)this == mc.player && m != null && m.isFlyEnabled()) {
            cir.setReturnValue(true);
        }
    }
}
