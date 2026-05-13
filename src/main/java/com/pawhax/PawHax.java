package com.pawhax;

import com.mojang.logging.LogUtils;
import com.pawhax.hud.NowPlayingHud;
import com.pawhax.modules.*;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class PawHax extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("pawhax");
    public static final HudGroup HUD_GROUP = new HudGroup("pawhax");

    @Override
    public void onInitialize() {
        LOG.info("loading pawhax....");

        // Modules
        //Modules.get().add(new ModuleExample());
        Modules.get().add(new PawChat());
        Modules.get().add(new AutoPawjob());
        Modules.get().add(new PearlGUI());
        Modules.get().add(new Pitch40AutoRocket());
        Modules.get().add(new PawtoAnvilRename());
        Modules.get().add(new PawtoDyeShulkers());
        Modules.get().add(new PawtoLogoutWhisper());
        Modules.get().add(new AntiAntiSpam());
        Modules.get().add(new AntiAntiAFK());
        Modules.get().add(new TailTrail());
        Modules.get().add(new InstantPaw());
        Modules.get().add(new InvResync());
        Modules.get().add(new DiagBounce());
        Modules.get().add(new BannerWebhook());
        Modules.get().add(new NowPlayingModule());

        LOG.info("imported larp modules........");
        LOG.info("rat successfully installed! :3");

        // Commands
        //Commands.add(new CommandExample());

        // HUD
        Hud.get().register(NowPlayingHud.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.pawhax";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("pawbase2b2t", "pawhax");
    }
}
