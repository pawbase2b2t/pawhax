package com.pawhax.modules;

import com.google.gson.*;
import com.pawhax.PawHax;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.block.entity.BannerBlockEntity;
import net.minecraft.component.type.BannerPatternsComponent;
import net.minecraft.util.math.BlockPos;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class BannerWebhook extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<String> webhookUrl = sgGeneral.add(new StringSetting.Builder()
        .name("webhook-url")
        .description("Discord webhook URL for banner alerts.")
        .defaultValue("")
        .build()
    );

    public final Setting<Boolean> sendImage = sgGeneral.add(new BoolSetting.Builder()
        .name("send-image")
        .description("Render and attach a PNG image of the banner.")
        .defaultValue(true)
        .build()
    );

    private final Set<String> allTimeSeen = Collections.synchronizedSet(new HashSet<>());
    private Path saveFile;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BannerWebhook-Sender");
        t.setDaemon(true);
        return t;
    });

    public BannerWebhook() {
        super(PawHax.CATEGORY, "banner-webhook", "Sends discovered banners to a Discord webhook.");
    }

    @Override
    public void onActivate() {
        saveFile = Paths.get("meteor-client", "pawhax", "banners_seen.json");
        loadFromDisk();
    }

    @Override
    public void onDeactivate() {}

    public void clearSession() {}

    public void handleBanner(BannerBlockEntity banner, BlockPos pos) {
        String url = webhookUrl.get();
        if (url == null || url.isEmpty()) return;

        String key = buildKey(banner);
        synchronized (allTimeSeen) {
            if (allTimeSeen.contains(key)) return;
            allTimeSeen.add(key);
        }
        saveToDisk();

        String message = buildMessage(pos, banner);
        byte[] imageBytes = null;
        if (sendImage.get()) {
            try {
                imageBytes = BannerRenderer.renderBanner(banner);
            } catch (Exception e) {
                PawHax.LOG.error("BannerWebhook: Failed to render banner image", e);
            }
        }

        final byte[] finalImage = imageBytes;
        EXECUTOR.submit(() -> {
            try {
                sendMultipart(url, message, finalImage);
            } catch (Exception e) {
                PawHax.LOG.error("BannerWebhook: Failed to send to Discord", e);
            }
        });
    }

    private String buildKey(BannerBlockEntity banner) {
        StringBuilder sb = new StringBuilder();
        sb.append(banner.getColorForState().getName()).append("|");
        BannerPatternsComponent patterns = banner.getPatterns();
        if (patterns != null) {
            for (BannerPatternsComponent.Layer layer : patterns.layers()) {
                sb.append(layer.pattern().value().assetId())
                  .append(":").append(layer.color().getName()).append(";");
            }
        }
        return sb.toString();
    }

    private String buildMessage(BlockPos pos, BannerBlockEntity banner) {
        StringBuilder sb = new StringBuilder();
        sb.append("🚩 **New Banner Pattern!**\n");
        sb.append("📍 **Coords:** `").append(pos.getX()).append(", ")
          .append(pos.getY()).append(", ").append(pos.getZ()).append("`\n");
        sb.append("🎨 **Base:** ").append(formatName(banner.getColorForState().getName())).append("\n");
        BannerPatternsComponent patterns = banner.getPatterns();
        if (patterns != null && !patterns.layers().isEmpty()) {
            int i = 1;
            for (BannerPatternsComponent.Layer layer : patterns.layers()) {
                String patternName = formatName(layer.pattern().value().assetId().getPath());
                String colorName = formatName(layer.color().getName());
                sb.append("**Pattern ").append(i++).append(":** ")
                  .append(colorName).append(" ").append(patternName).append("\n");
            }
        } else {
            sb.append("**Pattern:** Plain (no patterns)\n");
        }
        return sb.toString().trim();
    }

    private String formatName(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String word : raw.replace("_", " ").split(" ")) {
            if (!word.isEmpty())
                sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase()).append(" ");
        }
        return sb.toString().trim();
    }

    private void sendMultipart(String webhookUrl, String message, byte[] imageBytes) throws IOException {
        String boundary = "PawHaxBannerBoundary" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(webhookUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);

        try (OutputStream os = conn.getOutputStream();
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(os, "UTF-8"), true)) {

            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"payload_json\"\r\n");
            writer.append("Content-Type: application/json\r\n\r\n");
            String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
            writer.append("{\"content\":\"").append(escaped).append("\"}\r\n");
            writer.flush();

            if (imageBytes != null) {
                writer.append("--").append(boundary).append("\r\n");
                writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"banner.png\"\r\n");
                writer.append("Content-Type: image/png\r\n\r\n");
                writer.flush();
                os.write(imageBytes);
                os.flush();
                writer.append("\r\n");
            }

            writer.append("--").append(boundary).append("--\r\n");
            writer.flush();
        }

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            InputStream err = conn.getErrorStream();
            String body = err != null ? new String(err.readAllBytes()) : "(no body)";
            PawHax.LOG.error("BannerWebhook: Discord returned HTTP {}: {}", code, body);
        }
    }

    private void loadFromDisk() {
        if (saveFile == null || !Files.exists(saveFile)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(saveFile)).getAsJsonObject();
            for (JsonElement el : root.getAsJsonArray("seen")) {
                allTimeSeen.add(jsonEntryToKey(el.getAsJsonObject()));
            }
        } catch (Exception e) {
            PawHax.LOG.error("BannerWebhook: Failed to load seen banners", e);
        }
    }

    private void saveToDisk() {
        if (saveFile == null) return;
        try {
            Files.createDirectories(saveFile.getParent());
            JsonArray seen = new JsonArray();
            synchronized (allTimeSeen) {
                for (String key : allTimeSeen) seen.add(keyToJsonEntry(key));
            }
            JsonObject root = new JsonObject();
            root.add("seen", seen);
            Files.writeString(saveFile, new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (IOException e) {
            PawHax.LOG.error("BannerWebhook: Failed to save seen banners", e);
        }
    }

    private JsonObject keyToJsonEntry(String key) {
        int pipe = key.indexOf('|');
        JsonObject entry = new JsonObject();
        entry.addProperty("base", pipe >= 0 ? key.substring(0, pipe) : key);
        JsonArray layers = new JsonArray();
        if (pipe >= 0) {
            for (String layer : key.substring(pipe + 1).split(";")) {
                if (layer.isEmpty()) continue;
                int lastColon = layer.lastIndexOf(':');
                if (lastColon < 0) continue;
                JsonObject l = new JsonObject();
                l.addProperty("pattern", layer.substring(0, lastColon));
                l.addProperty("color", layer.substring(lastColon + 1));
                layers.add(l);
            }
        }
        entry.add("layers", layers);
        return entry;
    }

    private String jsonEntryToKey(JsonObject entry) {
        StringBuilder sb = new StringBuilder();
        sb.append(entry.get("base").getAsString()).append("|");
        JsonArray layers = entry.getAsJsonArray("layers");
        if (layers != null) {
            for (JsonElement el : layers) {
                JsonObject l = el.getAsJsonObject();
                sb.append(l.get("pattern").getAsString())
                  .append(":").append(l.get("color").getAsString()).append(";");
            }
        }
        return sb.toString();
    }
}
