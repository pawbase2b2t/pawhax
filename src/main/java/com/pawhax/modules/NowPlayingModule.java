package com.pawhax.modules;

import com.pawhax.PawHax;
import com.pawhax.util.SmtcProvider;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;

public class NowPlayingModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Print all SMTC property slot values to the game log each poll cycle.")
        .defaultValue(false)
        .onChanged(SmtcProvider::setDebug)
        .build()
    );

    public NowPlayingModule() {
        super(PawHax.CATEGORY, "now-playing", "Shows currently playing media in the HUD. Windows only.");
    }

    @Override
    public void onActivate() {
        SmtcProvider.start();
    }

    @Override
    public void onDeactivate() {
        SmtcProvider.stop();
    }
}
