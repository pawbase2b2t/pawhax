package com.pawhax.modules;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.pawhax.PawHax;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.nbt.NbtCompound;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class AntiAntiSpam extends Module {
    private static final String REPO_API_URL       = "https://api.github.com/repos/pawbase2b2t/anti-anti-spam/contents/";
    private static final String RAW_BASE_URL        = "https://raw.githubusercontent.com/pawbase2b2t/anti-anti-spam/main/";
    private static final long   REFRESH_INTERVAL_MS = 3 * 60_000L;

    private final SettingGroup sgCategories = settings.createGroup("Categories");

    // populated as categories are fetched; ConcurrentHashMap so background writes are safe
    private final Map<String, Setting<Boolean>> categoryToggles = new ConcurrentHashMap<>();
    // toggle states loaded from NBT before categories are fetched, so we can set the right default
    private final Map<String, Boolean> savedToggles = new HashMap<>();

    private volatile Map<String, List<Pattern>> patterns = Collections.emptyMap();
    private long    lastFetchMs = 0;
    private boolean firstFetch  = true;
    private final AtomicBoolean fetching = new AtomicBoolean(false);
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    public AntiAntiSpam() {
        super(PawHax.CATEGORY, "AntiAntiSpam", "chat larp remover");
    }

    @Override
    public void onActivate() {
        firstFetch = true;
        refresh();
    }

    @Override
    public void onDeactivate() {
        fetching.set(false);
        lastFetchMs = 0;
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = super.toTag();
        for (Map.Entry<String, Setting<Boolean>> e : categoryToggles.entrySet()) {
            tag.putBoolean("t:" + e.getKey(), e.getValue().get());
        }
        return tag;
    }

    @Override
    public Module fromTag(NbtCompound tag) {
        super.fromTag(tag);
        savedToggles.clear();
        for (String key : tag.getKeys()) {
            if (key.startsWith("t:")) savedToggles.put(key.substring(2), tag.getBoolean(key));
        }
        return this;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        if (System.currentTimeMillis() - lastFetchMs > REFRESH_INTERVAL_MS) refresh();
    }

    @EventHandler
    private void onMessageReceive(ReceiveMessageEvent event) {
        String text = event.getMessage().getString();
        for (Map.Entry<String, List<Pattern>> entry : patterns.entrySet()) {
            Setting<Boolean> toggle = categoryToggles.get(entry.getKey());
            if (toggle != null && !toggle.get()) continue;
            for (Pattern p : entry.getValue()) {
                if (p.matcher(text).find()) {
                    event.cancel();
                    return;
                }
            }
        }
    }

    private void refresh() {
        if (!fetching.compareAndSet(false, true)) return;
        lastFetchMs = System.currentTimeMillis();
        boolean isFirst = firstFetch;
        firstFetch = false;

        try {
            CompletableFuture.runAsync(() -> {
                try {
                    List<String> categories = fetchCategoryList();
                    if (categories == null) return;

                    Map<String, List<Pattern>> prev  = patterns;
                    Map<String, List<Pattern>> fresh = new LinkedHashMap<>();
                    for (String cat : categories) {
                        List<Pattern> compiled = fetchCategory(cat);
                        if (compiled != null) {
                            fresh.put(cat, compiled);
                            ensureToggle(cat);
                        }
                    }

                    for (Map.Entry<String, List<Pattern>> e : fresh.entrySet()) {
                        if (!prev.containsKey(e.getKey())) {
                            info("category added: " + e.getKey());
                        } else if (!samePatterns(prev.get(e.getKey()), e.getValue())) {
                            info("category updated: " + e.getKey());
                        }
                    }

                    patterns = fresh;
                } finally {
                    fetching.set(false);
                }
            });
        } catch (Exception e) {
            fetching.set(false);
            warning("failed to start fetch: " + e.getMessage());
        }
    }

    private void ensureToggle(String category) {
        if (categoryToggles.containsKey(category)) return;
        Setting<Boolean> toggle = sgCategories.add(new BoolSetting.Builder()
            .name(category)
            .description("filter '" + category + "' spam")
            .defaultValue(savedToggles.getOrDefault(category, true))
            .build()
        );
        categoryToggles.put(category, toggle);
    }

    private static boolean samePatterns(List<Pattern> a, List<Pattern> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++)
            if (!a.get(i).pattern().equals(b.get(i).pattern())) return false;
        return true;
    }

    private List<String> fetchCategoryList() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(REPO_API_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github.v3+json")
                .GET().build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                warning("failed to list categories: HTTP " + res.statusCode());
                return null;
            }

            List<String> out = new ArrayList<>();
            for (JsonElement el : JsonParser.parseString(res.body()).getAsJsonArray()) {
                String name = el.getAsJsonObject().get("name").getAsString();
                if (name.endsWith(".txt")) out.add(name.substring(0, name.length() - 4));
            }
            return out;
        } catch (Exception e) {
            warning("failed to list categories: " + e.getMessage());
            return null;
        }
    }

    private List<Pattern> fetchCategory(String category) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(RAW_BASE_URL + category + ".txt"))
                .timeout(Duration.ofSeconds(15))
                .GET().build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                warning("failed to fetch '" + category + "': HTTP " + res.statusCode());
                return null;
            }

            List<Pattern> compiled = new ArrayList<>();
            for (String line : res.body().split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                try {
                    compiled.add(Pattern.compile(line));
                } catch (Exception e) {
                    warning("invalid regex in '" + category + "': " + line);
                }
            }
            return compiled;
        } catch (Exception e) {
            warning("failed to fetch '" + category + "': " + e.getMessage());
            return null;
        }
    }
}
