package com.pawhax.modules;

import com.pawhax.PawHax;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;

import java.util.List;

public class PearlGUI extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<String>> labels = sgGeneral.add(new StringListSetting.Builder()
        .name("labels")
        .description("List of labels/bot names")
        .defaultValue(List.of())
        .build()
    );

    private final Setting<List<String>> commands = sgGeneral.add(new StringListSetting.Builder()
        .name("commands")
        .description("")
        .defaultValue(List.of())
        .build()
    );

    private final Setting<Integer> antispamBytesMessage = sgGeneral.add(new IntSetting.Builder()
        .name("anti-spam-bytes-message")
        .description("")
        .defaultValue(8)
        .min(1)
        .sliderMax(16)
        .build()
    );

    private final Setting<Integer> antispamBytesChat = sgGeneral.add(new IntSetting.Builder()
        .name("anti-spam-bytes-chat")
        .description("")
        .defaultValue(4)
        .min(1)
        .sliderMax(16)
        .build()
    );

    public PearlGUI() {
        super(PawHax.CATEGORY, "pearl-gui", "epic selection pizza pie cake wheel :3");
    }

    @Override
    public void onActivate() {
        mc.setScreen(new PearlWheelScreen(labels.get(), commands.get()));
        toggle();
    }
}
