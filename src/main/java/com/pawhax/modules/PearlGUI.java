package com.pawhax.modules;

import com.pawhax.PawHax;
import meteordevelopment.meteorclient.settings.BoolSetting;
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
        .description("List of labels/usernames.")
        .defaultValue(List.of())
        .build()
    );

    private final Setting<List<String>> commands = sgGeneral.add(new StringListSetting.Builder()
        .name("commands")
        .description("List of command prefixes (must match labels by index).")
        .defaultValue(List.of())
        .build()
    );

    private final Setting<Integer> antispamBytesMessage = sgGeneral.add(new IntSetting.Builder()
        .name("anti-spam-bytes-message")
        .description("Number of bytes to add to the antispam for a message.")
        .defaultValue(8)
        .min(1)
        .sliderMax(16)
        .build()
    );

    private final Setting<Integer> antispamBytesChat = sgGeneral.add(new IntSetting.Builder()
        .name("anti-spam-bytes-chat")
        .description("Number of bytes to add to the antispam for a public chat.")
        .defaultValue(4)
        .min(1)
        .sliderMax(16)
        .build()
    );

    private final Setting<Boolean> scrollWheelPaging = sgGeneral.add(new BoolSetting.Builder()
        .name("scroll-wheel-paging")
        .description("Change pages with the scroll wheel.")
        .defaultValue(true)
        .build()
    );

    public PearlGUI() {
        super(PawHax.CATEGORY, "pearl-gui", "Pearl GUI module.");
    }

    @Override
    public void onActivate() {
        mc.setScreen(new PearlWheelScreen(labels.get(), commands.get(), antispamBytesMessage.get(), antispamBytesChat.get(), scrollWheelPaging.get()));
        toggle();
    }
}
