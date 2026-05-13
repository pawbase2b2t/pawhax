package com.pawhax.hud;

import com.pawhax.PawHax;
import com.pawhax.util.SmtcProvider;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

public class NowPlayingHud extends HudElement {
    public static final HudElementInfo<NowPlayingHud> INFO =
        new HudElementInfo<>(PawHax.HUD_GROUP, "now-playing", "Shows currently playing media.", NowPlayingHud::new);

    private final SettingGroup sgGeneral = settings.createGroup("General");

    private final Setting<SettingColor> textColor = sgGeneral.add(new ColorSetting.Builder()
        .name("text-color")
        .description("Color of the now playing text.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private final Setting<Boolean> artistFirst = sgGeneral.add(new BoolSetting.Builder()
        .name("artist-first")
        .description("Show 'Artist - Title'. Uncheck for 'Title - Artist'.")
        .defaultValue(true)
        .build()
    );

    private static final Color PLACEHOLDER_COLOR = new Color(200, 200, 200, 120);
    private static final String PLACEHOLDER       = "Artist - Title";

    private String cachedDisplay = null;

    public NowPlayingHud() {
        super(INFO);
    }

    @Override
    public void tick(HudRenderer renderer) {
        cachedDisplay = buildDisplay();
        setSize(
            renderer.textWidth(cachedDisplay != null ? cachedDisplay : PLACEHOLDER),
            renderer.textHeight()
        );
    }

    @Override
    public void render(HudRenderer renderer) {
        if (cachedDisplay != null) {
            SettingColor color = textColor.get();
            color.update();
            renderer.text(cachedDisplay, x, y, color, true);
        } else if (isInEditor()) {
            renderer.text(PLACEHOLDER, x, y, PLACEHOLDER_COLOR, true);
        }
    }

    private String buildDisplay() {
        String[] track = SmtcProvider.getTrack();
        if (track == null) return null;
        String title  = track[0];
        String artist = track[1];
        if (title == null && artist == null) return null;
        if (title  == null) return artist;
        if (artist == null) return title;
        return artistFirst.get() ? artist + " - " + title : title + " - " + artist;
    }
}
