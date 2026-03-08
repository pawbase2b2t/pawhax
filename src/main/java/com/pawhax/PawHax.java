package com.pawhax;

import com.mojang.logging.LogUtils;
import com.pawhax.modules.PawtoAnvilRename;
import com.pawhax.modules.PawtoDyeShulkers;
import com.pawhax.modules.PawtoLogoutWhisper;
import com.pawhax.modules.AutoPawjob;
import com.pawhax.modules.PawChat;
import com.pawhax.modules.PearlGUI;
import com.pawhax.modules.Pitch40AutoRocket;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
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
        LOG.info("Initializing pawhax");

        // Modules
        //Modules.get().add(new ModuleExample());
        Modules.get().add(new PawChat());
        Modules.get().add(new AutoPawjob());
        Modules.get().add(new PearlGUI());
        Modules.get().add(new Pitch40AutoRocket());
        Modules.get().add(new PawtoAnvilRename());
        Modules.get().add(new PawtoDyeShulkers());
        Modules.get().add(new PawtoLogoutWhisper());

        // Commands
        //Commands.add(new CommandExample());

        // HUD
        //Hud.get().register(HudExample.INFO);
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
        return new GithubRepo("CrisisSheep", "pawhax");
    }
}
