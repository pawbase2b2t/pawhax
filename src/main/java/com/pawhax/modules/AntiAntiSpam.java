package com.pawhax.modules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.pawhax.PawHax;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

public class AntiAntiSpam extends Module {
    private static final String REPO_API_URL = "https://api.github.com/repos/pawbase2b2t/anti-anti-spam/contents/";
    private static final String RAW_BASE_URL = "https://raw.githubusercontent.com/pawbase2b2t/anti-anti-spam/main/";

    private static final long REFRESH_INTERVAL_MS = 3 * 60_000L;

    private final SettingGroup sgCategories = settings.createGroup("Categories");

    private final Map<String, Setting<Boolean>> categoryToggles = new LinkedHashMap<>();
    private final Map<String, List<Pattern>> categoryPatterns = new LinkedHashMap<>();
    private long lastFetchMs = 0;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    public AntiAntiSpam() {
        super(PawHax.CATEGORY, "AntiAntiSpam", "chat larp remover");
    }

    @Override
    public void onActivate() {
        refresh();
    }

    @Override
    public void onDeactivate() {
        categoryPatterns.clear();
        lastFetchMs = 0;
    }

    @EventHandler
    private void onMessageReceive(ReceiveMessageEvent event) {
        if (System.currentTimeMillis() - lastFetchMs > REFRESH_INTERVAL_MS) {
            refresh();
        }

        String text = event.getMessage().getString();
        for (var entry : categoryPatterns.entrySet()) {
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
        lastFetchMs = System.currentTimeMillis();

        CompletableFuture.runAsync(() -> {
            List<String> categories = fetchCategoryList();
            if (categories == null) return;

            for (String category : categories) {
                fetchCategory(category);
            }

        });
    }

    private List<String> fetchCategoryList() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(REPO_API_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github.v3+json")
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                warning("Failed to list categories: HTTP " + response.statusCode());
                return null;
            }

            JsonArray files = JsonParser.parseString(response.body()).getAsJsonArray();
            List<String> categories = new ArrayList<>();
            for (JsonElement el : files) {
                String name = el.getAsJsonObject().get("name").getAsString();
                if (name.endsWith(".txt")) {
                    categories.add(name.substring(0, name.length() - 4));
                }
            }
            return categories;
        } catch (Exception e) {
            warning("Failed to list categories: " + e.getMessage());
            return null;
        }
    }

    private void ensureToggle(String category, String description) {
        Setting<Boolean> existing = categoryToggles.get(category);
        if (existing != null && existing.description.equals(description)) return;

        boolean currentValue = existing != null ? existing.get() : true;

        Setting<Boolean> toggle = sgCategories.add(new BoolSetting.Builder()
            .name(category)
            .description(description)
            .defaultValue(currentValue)
            .build()
        );
        categoryToggles.put(category, toggle);
    }

    private void fetchCategory(String category) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(RAW_BASE_URL + category + ".txt"))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                warning("Failed to fetch category '" + category + "': HTTP " + response.statusCode());
                return;
            }

            String[] lines = response.body().split("\n");
            String description = "Enable the '" + category + "' filter rules.";
            if (lines.length > 0) {
                String firstLine = lines[0].trim();
                if (firstLine.startsWith("#")) {
                    description = firstLine.substring(1).trim();
                }
            }

            ensureToggle(category, description);

            List<Pattern> compiled = new ArrayList<>();
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                try {
                    compiled.add(Pattern.compile(line));
                } catch (Exception e) {
                    warning("Invalid regex in '" + category + "': " + line);
                }
            }
            categoryPatterns.put(category, compiled);
        } catch (Exception e) {
            warning("Failed to fetch category '" + category + "': " + e.getMessage());
        }
    }
}
