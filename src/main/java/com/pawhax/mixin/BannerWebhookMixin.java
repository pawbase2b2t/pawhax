package com.pawhax.mixin;

import com.pawhax.modules.BannerWebhook;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.block.entity.BannerBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public class BannerWebhookMixin {

    @Inject(method = "read", at = @At("TAIL"))
    private void onRead(CallbackInfo ci) {
        if (!((Object) this instanceof BannerBlockEntity banner)) return;
        if (banner.getWorld() == null || !banner.getWorld().isClient()) return;
        BannerWebhook module = Modules.get().get(BannerWebhook.class);
        if (module == null || !module.isActive()) return;
        module.handleBanner(banner, banner.getPos());
    }
}
