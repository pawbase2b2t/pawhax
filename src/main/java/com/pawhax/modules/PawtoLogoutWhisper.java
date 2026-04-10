package com.pawhax.modules;

import com.pawhax.PawHax;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class PawtoLogoutWhisper extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> delayDisconnect = sgGeneral.add(new BoolSetting.Builder()
        .name("delay-disconnect")
        .description("Wait a number of seconds before disconnecting.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> delaySeconds = sgGeneral.add(new IntSetting.Builder()
        .name("delay-seconds")
        .description("How many seconds to wait before disconnecting.")
        .defaultValue(5)
        .min(1)
        .sliderMax(30)
        .visible(delayDisconnect::get)
        .build()
    );

    private final Setting<Boolean> disableAutoReconnect = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-auto-reconnect")
        .description("Disables Meteor's AutoReconnect module when a logout is triggered.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> externalAutoReconnect = sgGeneral.add(new BoolSetting.Builder()
        .name("ext-auto-reconnect")
        .description("Send a command to disable AutoReconnect on an external client (e.g. RusherHack, Mio).")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> externalCommand = sgGeneral.add(new StringSetting.Builder()
        .name("ext-reconnect-cmd")
        .description("Command to send to disable AutoReconnect on the external client.")
        .defaultValue("*toggle autoreconnect false")
        .visible(externalAutoReconnect::get)
        .build()
    );

    private final Setting<Boolean> disableOnTrigger = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-on-trigger")
        .description("Disables this module after a logout is triggered, preventing repeated logouts on reconnect.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<String>> whitelist = sgGeneral.add(new StringListSetting.Builder()
        .name("whitelist")
        .description("Players whose whispers can trigger a logout. Only exact usernames.")
        .defaultValue(new ArrayList<>())
        .build()
    );

    // Matches the 2b2t whisper format: "{player} whispers: {message}"
    private static final Pattern WHISPER_PATTERN = Pattern.compile("^(\\w+) whispers: (.+)$");
    // Valid triggers: !logout, !log, !disconnect, !dc. Optional trailing text allows for antispam and -r reason.
    private static final Pattern TRIGGER_PATTERN = Pattern.compile("^(!logout|!log|!disconnect|!dc)( .*)?$");

    private boolean disconnectPending = false;
    private long disconnectAtMs = -1;
    private String pendingDisconnectMessage = null;

    public PawtoLogoutWhisper() {
        super(PawHax.CATEGORY, "PawtoLogoutWhisper", "auto logs you on msg");
    }

    @Override
    public void onDeactivate() {
        disconnectPending = false;
        disconnectAtMs = -1;
        pendingDisconnectMessage = null;
    }

    @EventHandler
    private void onMessageReceive(ReceiveMessageEvent event) {
        String raw = event.getMessage().getString();

        // Check if this is a whisper at all
        Matcher whisperMatcher = WHISPER_PATTERN.matcher(raw);
        if (!whisperMatcher.matches()) return;

        String sender = whisperMatcher.group(1);
        String message = whisperMatcher.group(2);

        // Check if the whisper contains a valid trigger keyword
        Matcher triggerMatcher = TRIGGER_PATTERN.matcher(message);
        if (!triggerMatcher.matches()) return;

        boolean whitelisted = whitelist.get().stream()
            .anyMatch(name -> name.equalsIgnoreCase(sender));

        if (!whitelisted) {
            final String rejectSender = sender;
            mc.execute(() -> ChatUtils.sendPlayerMsg("/msg " + rejectSender + " You are not whitelisted on PawtoLogoutWhisper! >:("));
            return;
        }

        // Parse optional reason flag: "-r <reason>" anywhere in the trailing text
        String reason = null;
        String trailing = triggerMatcher.group(2); // " <antispam and/or -r reason>" or null
        if (trailing != null) {
            String trimmed = trailing.trim();
            if (trimmed.startsWith("-r ")) {
                reason = trimmed.substring(3).trim();
                if (reason.isEmpty()) reason = null;
            }
        }

        String playerName = mc.player != null ? mc.player.getName().getString() : "You";

        // Build the disconnect screen message shown after the kick
        StringBuilder disconnectMsg = new StringBuilder();
        disconnectMsg.append("[PawtoLogoutWhisper]\n");
        disconnectMsg.append(playerName).append(" was disconnected by ").append(sender);
        if (reason != null) {
            disconnectMsg.append("\n[REASON]: ").append(reason);
        }
        pendingDisconnectMessage = disconnectMsg.toString();

        // Reply to sender and arm the disconnect timer
        if (delayDisconnect.get()) {
            int secs = delaySeconds.get();
            final String delaySender = sender;
            mc.execute(() -> ChatUtils.sendPlayerMsg("/msg " + delaySender + " Disconnecting in " + secs + " second" + (secs == 1 ? "" : "s") + "..."));
            disconnectAtMs = System.currentTimeMillis() + (secs * 1000L);
        } else {
            final String nowSender = sender;
            mc.execute(() -> ChatUtils.sendPlayerMsg("/msg " + nowSender + " Disconnecting now..."));
            disconnectAtMs = System.currentTimeMillis();
        }

        disconnectPending = true;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!disconnectPending) return;
        if (mc.player == null) {
            disconnectPending = false;
            return;
        }

        if (System.currentTimeMillis() < disconnectAtMs) return;

        disconnectPending = false;

        // Disable Meteor's AutoReconnect so we don't immediately reconnect and loop
        if (disableAutoReconnect.get()) {
            AutoReconnect autoReconnect = Modules.get().get(AutoReconnect.class);
            if (autoReconnect.isActive()) autoReconnect.toggle();
        }

        // Send the external client command before disconnect fires, so the client can intercept it
        if (externalAutoReconnect.get()) {
            String cmd = externalCommand.get();
            if (!cmd.isBlank()) {
                mc.player.networkHandler.sendChatMessage(cmd);
            }
        }

        // Fire a fake disconnect packet to kick ourselves off the server
        mc.player.networkHandler.onDisconnect(new DisconnectS2CPacket(
            Text.literal(pendingDisconnectMessage)
        ));

        pendingDisconnectMessage = null;
        disconnectAtMs = -1;

        if (disableOnTrigger.get()) toggle();
    }
}
