package com.pawhax.modules;

import com.pawhax.PawHax;
import com.pawhax.util.SmtcProvider;
import meteordevelopment.meteorclient.systems.modules.Module;

public class NowPlayingModule extends Module {
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
