package com.pawhax.modules;


import com.pawhax.PawHax;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

public class PawChat extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> prefix = sgGeneral.add(new BoolSetting.Builder()
        .name("prefix")
        .description("Adds a prefix to your messages.")
        .defaultValue(true)
        .build()
    );

    public final Setting<String> prefixText = sgGeneral.add(new StringSetting.Builder()
        .name("prefix-text")
        .description("Characters to put before the start of your message.")
        .defaultValue(">")
        .build()
    );

    private final Setting<Boolean> suffix = sgGeneral.add(new BoolSetting.Builder()
        .name("suffix")
        .description("Adds a suffix to your messages.")
        .defaultValue(false)
        .build()
    );

    public final Setting<String> suffixText = sgGeneral.add(new StringSetting.Builder()
        .name("suffix-text")
        .description("Characters to put after the end of your message.")
        .defaultValue("| pawhax")
        .build()
    );


    private final Setting<Boolean> antispam = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-spam")
        .description("Adds pawhax branded antispam to the end of your message.")
        .defaultValue(true)
        .build()
    );

    public final Setting<String> antispamFormat = sgGeneral.add(new StringSetting.Builder()
        .name("anti-spam-format")
        .description("Format of the antispam string. Use '%' to delegate where the antispam should be placed.")
        .defaultValue("<<pawhax%>>")
        .build()
    );

    private final Setting<Integer> antispamBytes = sgGeneral.add(new IntSetting.Builder()
        .name("anti-spam-bytes")
        .description("Number of bytes to add to the antispam.")
        .defaultValue(4)
        .min(1)
        .sliderMax(8)
        .build()
    );

    private static final java.util.Random RANDOM = new java.util.Random();

    public PawChat() {
        super(PawHax.CATEGORY, "paw-chat", "for flexing on chuds");
    }

    @EventHandler
    private void onSendMessage(SendMessageEvent e) {
        String msg = e.message;
        if (msg == null || msg.isEmpty()) return;

        // ignore commands
        if (msg.startsWith("/") || msg.startsWith(";") || msg.startsWith(".") || msg.startsWith("#") || msg.startsWith("!") || msg.startsWith("*")) return;

        String newMsg = msg;

        // add prefix if enabled
        if (this.prefix.get()) {
            String prefixText = this.prefixText.get();
            newMsg = prefixText + " " + newMsg;
        }

        // add suffix if enabled
        if (this.suffix.get()) {
            String suffixText = this.suffixText.get();
            newMsg = newMsg + " " + suffixText;
        }

        // add antispam if enabled
        if (this.antispam.get()) {
            // create random bytes
            byte[] bytes = new byte[this.antispamBytes.get()];
            RANDOM.nextBytes(bytes);

            // add them to a string
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }

            // place into format
            String antispamText = antispamFormat.get().replace("%", sb.toString());
            newMsg = newMsg + " " + antispamText;
        }

        e.message = newMsg;
    }
}
