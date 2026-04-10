package com.pawhax.modules;

import com.pawhax.PawHax;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.AnvilBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.*;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class PawtoAnvilRename extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> removeNames = sgGeneral.add(new BoolSetting.Builder()
        .name("remove-item-names")
        .description("Remove custom names from items instead of applying one.")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> renameText = sgGeneral.add(new StringSetting.Builder()
        .name("rename-text")
        .description("The name to give items in the anvil. Leave empty to reset to default name.")
        .defaultValue("My Item")
        .visible(() -> !removeNames.get())
        .build()
    );

    // Hidden - persists the last 10 used names across sessions
    private final Setting<List<String>> recentNames = sgGeneral.add(new StringListSetting.Builder()
        .name("recent-names")
        .description("Recently used rename strings (auto-managed).")
        .defaultValue(new ArrayList<>())
        .visible(() -> false)
        .build()
    );

    private final Setting<Boolean> onlyOneType = sgGeneral.add(new BoolSetting.Builder()
        .name("only-one-item-type")
        .description("Only rename a specific item type.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Item> targetItem = sgGeneral.add(new ItemSetting.Builder()
        .name("target-item")
        .description("The item type to rename.")
        .defaultValue(Items.SHULKER_BOX)
        .visible(onlyOneType::get)
        .build()
    );

    private final Setting<Boolean> onlyShulkers = sgGeneral.add(new BoolSetting.Builder()
        .name("only-shulkers")
        .description("Only rename shulker boxes (any colour).")
        .defaultValue(false)
        .visible(() -> !onlyOneType.get())
        .build()
    );

    private final Setting<Boolean> onlyRenamed = sgGeneral.add(new BoolSetting.Builder()
        .name("only-renamed")
        .description("Only process items that already have a custom name.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> clickDelay = sgGeneral.add(new IntSetting.Builder()
        .name("click-delay")
        .description("Ticks to wait between actions.")
        .defaultValue(5)
        .min(1)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> autoXP = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-xp")
        .description("Automatically throw XP bottles to afford renames.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> disableOnDone = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-on-done")
        .description("Automatically disable the module when no more items can be renamed.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> closeOnDone = sgGeneral.add(new BoolSetting.Builder()
        .name("close-screen")
        .description("Automatically close the anvil screen when no more items can be renamed.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pingOnDone = sgGeneral.add(new BoolSetting.Builder()
        .name("sound-ping")
        .description("Play a sound cue when no more items can be renamed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoReopen = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-reopen-anvil")
        .description("Automatically reopen the anvil if it breaks and a new one falls into place.")
        .defaultValue(false)
        .build()
    );

    private enum State {
        IDLE,
        WAITING_FOR_INPUT,   // item shift-clicked in, waiting for it to appear in slot 0
        WAITING_FOR_RENAME,  // setNewItemName sent, waiting for output slot to populate
        WAITING_FOR_OUTPUT,  // output taken, waiting for slot 2 to clear
        WAITING_FOR_BOTTLES  // bottle swapped into hand, waiting for it to appear in hotbar
    }

    private enum ReopenState {
        IDLE,
        WATCHING_FOR_AIR,   // GUI closed, waiting to confirm the block went to air (anvil broke)
        WATCHING_FOR_ANVIL, // saw air at the pos, now waiting for a new anvil to land
        WAITING_TO_CLICK    // new anvil detected, waiting 250ms before clicking
    }

    private State state = State.IDLE;
    private int delay = 0;
    private TextFieldWidget cachedTextField = null; // cached once per anvil open to avoid repeated reflection

    private boolean notified = false;

    private ReopenState reopenState = ReopenState.IDLE;
    private BlockPos watchedAnvilPos = null;
    private long reopenWatchDeadlineMs = -1;
    private long reopenClickAtMs = -1;

    private static final TextColor DARK_GRAY = TextColor.fromRgb(0x555555);
    private static final TextColor LIGHT_GRAY = TextColor.fromRgb(0xAAAAAA);

    public PawtoAnvilRename() {
        super(PawHax.CATEGORY, "PawtoAnvilRename", "renames stuffs in anvils");
    }

    // Adds a dropdown to the module's settings screen for quickly selecting a recent name
    @Override
    public WWidget getWidget(GuiTheme theme) {
        if (removeNames.get()) return null;

        List<String> recent = recentNames.get();
        String[] values = new String[recent.size() + 1];
        values[0] = "Previously Named";
        for (int i = 0; i < recent.size(); i++) values[i + 1] = recent.get(i);

        WDropdown<String> dropdown = theme.dropdown(values, "Previously Named");
        dropdown.action = () -> {
            String selected = dropdown.get();
            if (selected.equals("Previously Named")) return;
            findRenameTextBox(renameText.get(), selected);
            renameText.set(selected);
            dropdown.set("Previously Named");
        };

        return dropdown;
    }

    // Walks the Meteor settings GUI widget tree to find and update the rename-text text box
    // so the displayed value updates live when a recent name is selected from the dropdown
    private void findRenameTextBox(String currentValue, String newValue) {
        if (!(mc.currentScreen instanceof meteordevelopment.meteorclient.gui.WindowScreen ws)) return;
        try {
            java.lang.reflect.Field f = meteordevelopment.meteorclient.gui.WindowScreen.class.getDeclaredField("window");
            f.setAccessible(true);
            meteordevelopment.meteorclient.gui.widgets.containers.WContainer root =
                (meteordevelopment.meteorclient.gui.widgets.containers.WContainer) f.get(ws);
            walkForTextBox(root, currentValue, newValue);
        } catch (Exception ignored) {}
    }

    private boolean walkForTextBox(meteordevelopment.meteorclient.gui.widgets.containers.WContainer container,
                                   String currentValue, String newValue) {
        for (meteordevelopment.meteorclient.gui.utils.Cell<?> cell : container.cells) {
            meteordevelopment.meteorclient.gui.widgets.WWidget widget = cell.widget();
            if (widget instanceof WTextBox tb && tb.get().equals(currentValue)) {
                tb.set(newValue);
                return true;
            }
            if (widget instanceof meteordevelopment.meteorclient.gui.widgets.containers.WContainer wc) {
                if (walkForTextBox(wc, currentValue, newValue)) return true;
            }
        }
        return false;
    }

    @Override
    public void onActivate() {
        resetState();
        resetReopenState();
    }

    @Override
    public void onDeactivate() {
        resetState();
        resetReopenState();
    }

    private void resetState() {
        state = State.IDLE;
        delay = 0;
        cachedTextField = null;
        notified = false;
    }

    private void resetReopenState() {
        reopenState = ReopenState.IDLE;
        watchedAnvilPos = null;
        reopenWatchDeadlineMs = -1;
        reopenClickAtMs = -1;
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (!autoReopen.get()) return;
        if (!(event.screen instanceof AnvilScreen)) return;

        // Reset any in-progress reopen state cleanly before recording new pos
        resetReopenState();

        // Record the block pos the player is looking at when the anvil opens
        if (mc.crosshairTarget instanceof BlockHitResult bhr) {
            watchedAnvilPos = bhr.getBlockPos();
        }
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (!autoReopen.get()) return;
        if (reopenState == ReopenState.IDLE || watchedAnvilPos == null) return;
        if (!event.pos.equals(watchedAnvilPos)) return;

        boolean newIsAnvil = event.newState.getBlock() instanceof AnvilBlock;
        boolean newIsAir = event.newState.isAir();

        switch (reopenState) {
            case WATCHING_FOR_AIR -> {
                if (newIsAir) {
                    // Anvil broke - wait up to 1 second for a replacement to land
                    reopenState = ReopenState.WATCHING_FOR_ANVIL;
                    reopenWatchDeadlineMs = System.currentTimeMillis() + 1000L;
                } else if (newIsAnvil) {
                    // Block changed to a different anvil damage state - not a break, stop watching
                    reopenState = ReopenState.IDLE;
                } else {
                    // Something else entirely replaced the anvil - give up
                    resetReopenState();
                }
            }
            case WATCHING_FOR_ANVIL -> {
                if (newIsAnvil) {
                    // Replacement anvil landed - schedule the reopen click in 250ms
                    reopenState = ReopenState.WAITING_TO_CLICK;
                    reopenClickAtMs = System.currentTimeMillis() + 250L;
                }
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        boolean reopenEnabled = autoReopen.get();

        if (reopenEnabled) {
            handleReopenTick();
        }

        if (!(mc.currentScreen instanceof AnvilScreen anvilScreen)
            || !(mc.player.currentScreenHandler instanceof AnvilScreenHandler handler)) {

            // GUI just closed - start watching if reopen is enabled, we have a pos, and aren't already watching
            if (reopenEnabled && watchedAnvilPos != null && reopenState == ReopenState.IDLE) {
                BlockState currentState = mc.world.getBlockState(watchedAnvilPos);
                if (currentState.getBlock() instanceof AnvilBlock) {
                    // Block still there - start watching for it to go to air
                    reopenState = ReopenState.WATCHING_FOR_AIR;
                    reopenWatchDeadlineMs = System.currentTimeMillis() + 1000L;
                } else if (currentState.isAir()) {
                    // Already air - block update fired before this tick, skip straight to watching for replacement
                    reopenState = ReopenState.WATCHING_FOR_ANVIL;
                    reopenWatchDeadlineMs = System.currentTimeMillis() + 1000L;
                }
            }

            resetState();
            return;
        }

        // Anvil slot layout: 0=first input, 1=second input, 2=output, 3-29=main inventory, 30-38=hotbar
        ItemStack input1 = handler.getSlot(0).getStack();
        ItemStack output = handler.getSlot(2).getStack();

        // Wait states - hold off until the server confirms the previous action
        switch (state) {
            case WAITING_FOR_INPUT -> {
                if (input1.isEmpty()) return;
                state = State.IDLE;
                delay = 0;
                return;
            }
            case WAITING_FOR_RENAME -> {
                if (output.isEmpty()) return;
                state = State.IDLE;
                delay = 0;
                return;
            }
            case WAITING_FOR_OUTPUT -> {
                if (!output.isEmpty()) return;
                state = State.IDLE;
                delay = 0;
                return;
            }
            case WAITING_FOR_BOTTLES -> {
                // Wait until a bottle appears in the hotbar before throwing
                boolean bottleInHotbar = false;
                for (int i = 0; i < 9; i++) {
                    if (mc.player.getInventory().getStack(i).getItem() == Items.EXPERIENCE_BOTTLE) {
                        bottleInHotbar = true;
                        break;
                    }
                }
                if (!bottleInHotbar) return;
                state = State.IDLE;
                delay = 0;
                return;
            }
        }

        delay++;
        if (delay <= clickDelay.get()) return;
        delay = 0;

        String targetName = removeNames.get() ? "" : renameText.get();
        boolean filterOneType = onlyOneType.get();
        boolean filterShulkers = onlyShulkers.get();
        boolean filterRenamed = onlyRenamed.get();
        Item filterItem = targetItem.get();

        int cost = handler.getLevelCost();
        int playerLevels = mc.player.experienceLevel;

        // If there's a correctly renamed item in the output slot, take it
        if (!output.isEmpty() && itemMatchesTarget(output, targetName)) {
            if (playerLevels < cost && !mc.player.isInCreativeMode() && autoXP.get()) {
                throwXPBottle(handler);
                return;
            }
            if (playerLevels >= cost || mc.player.isInCreativeMode()) {
                mc.interactionManager.clickSlot(handler.syncId, 2, 0, SlotActionType.QUICK_MOVE, mc.player);
                if (!targetName.isEmpty()) recordRecentName(targetName);
                state = State.WAITING_FOR_OUTPUT;
                return;
            }
        }

        // If there's an item in the input slot that still needs renaming, write the name
        if (!input1.isEmpty()) {
            boolean needsRename = targetName.isEmpty()
                ? input1.get(DataComponentTypes.CUSTOM_NAME) != null || output.isEmpty()
                : !itemMatchesTarget(input1, targetName);

            if (needsRename) {
                if (cachedTextField == null) cachedTextField = getTextField(anvilScreen);
                if (cachedTextField != null) cachedTextField.setText(targetName);
                handler.setNewItemName(targetName);
                state = State.WAITING_FOR_RENAME;
                return;
            }
        }

        // Input slot is empty - find the next eligible item in inventory and shift-click it in
        if (input1.isEmpty()) {
            for (int i = 3; i < 39; i++) {
                ItemStack stack = handler.getSlot(i).getStack();
                if (stack.isEmpty()) continue;
                if (filterRenamed && stack.get(DataComponentTypes.CUSTOM_NAME) == null) continue;
                if (filterOneType && stack.getItem() != filterItem) continue;
                if (filterShulkers && !filterOneType && !isShulker(stack)) continue;
                if (itemMatchesTarget(stack, targetName)) continue;

                mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                state = State.WAITING_FOR_INPUT;
                notified = false; // new item found, reset so ping fires again if we run dry later
                return;
            }
            // No eligible items left in inventory - all done
            onFinished();
        }
    }

    private void handleReopenTick() {
        if (reopenState == ReopenState.IDLE || watchedAnvilPos == null) return;

        long now = System.currentTimeMillis();

        // Deadline expired - player closed manually or no replacement came in time
        if ((reopenState == ReopenState.WATCHING_FOR_AIR || reopenState == ReopenState.WATCHING_FOR_ANVIL)
            && now > reopenWatchDeadlineMs) {
            resetReopenState();
            return;
        }

        // Fire the reopen click once the delay has elapsed
        if (reopenState == ReopenState.WAITING_TO_CLICK && now >= reopenClickAtMs) {
            reopenState = ReopenState.IDLE;
            reopenClickAtMs = -1;

            if (!(mc.currentScreen instanceof AnvilScreen) && mc.player != null) {
                mc.interactionManager.interactBlock(
                    mc.player,
                    Hand.MAIN_HAND,
                    new BlockHitResult(Vec3d.ofCenter(watchedAnvilPos), Direction.UP, watchedAnvilPos, false)
                );
            }
        }
    }

    private void onFinished() {
        if (notified) return;
        notified = true;
        if (disableOnDone.get()) toggle();
        if (mc.player == null) return;
        if (closeOnDone.get()) mc.player.closeHandledScreen();
        if (pingOnDone.get()) mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        mc.player.sendMessage(pawHaxMessage("PawtoAnvilRename", "Finished renaming items!"), false);
    }

    private static MutableText pawHaxMessage(String moduleName, String message) {
        TextColor themeColor = TextColor.fromRgb(MeteorClient.ADDON.color.getPacked());
        return Text.empty()
            .append(Text.literal("[").setStyle(Style.EMPTY.withColor(DARK_GRAY)))
            .append(Text.literal(moduleName).setStyle(Style.EMPTY.withColor(themeColor)))
            .append(Text.literal("] ").setStyle(Style.EMPTY.withColor(DARK_GRAY)))
            .append(Text.literal(message).setStyle(Style.EMPTY.withColor(LIGHT_GRAY).withItalic(true)));
    }

    // Push the used name to the front of the recent list, cap at 10 entries
    private void recordRecentName(String name) {
        List<String> recent = new ArrayList<>(recentNames.get());
        recent.remove(name);
        recent.add(0, name);
        if (recent.size() > 10) recent.subList(10, recent.size()).clear();
        recentNames.set(recent);
    }

    private boolean itemMatchesTarget(ItemStack stack, String targetName) {
        if (targetName.isEmpty()) {
            return stack.get(DataComponentTypes.CUSTOM_NAME) == null;
        }
        Text customName = stack.get(DataComponentTypes.CUSTOM_NAME);
        return customName != null && customName.getString().equals(targetName);
    }

    // Throw an XP bottle to gain levels for the rename cost.
    // Uses a SWAP click to move the bottle into the currently selected hotbar slot
    // rather than InvUtils.swap(), which would close the anvil screen.
    private void throwXPBottle(AnvilScreenHandler handler) {
        if (mc.player == null || mc.interactionManager == null) return;

        int selectedSlot = mc.player.getInventory().selectedSlot;

        // Already holding a bottle - throw it right away
        if (mc.player.getInventory().getStack(selectedSlot).getItem() == Items.EXPERIENCE_BOTTLE) {
            throwBottle();
            return;
        }

        // Look for a bottle in hotbar first, then main inventory
        int bottleSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.EXPERIENCE_BOTTLE) {
                bottleSlot = i;
                break;
            }
        }
        if (bottleSlot == -1) {
            for (int i = 9; i < 36; i++) {
                if (mc.player.getInventory().getStack(i).getItem() == Items.EXPERIENCE_BOTTLE) {
                    bottleSlot = i;
                    break;
                }
            }
        }
        if (bottleSlot == -1) return;

        // Remap player inventory slot to anvil handler slot.
        // Hotbar 0-8 -> handler slots 30-38. Main inventory 9-35 -> handler slots 3-29 (slot - 6).
        int anvilBottleSlot = bottleSlot < 9 ? bottleSlot + 30 : bottleSlot - 6;

        mc.interactionManager.clickSlot(handler.syncId, anvilBottleSlot, selectedSlot, SlotActionType.SWAP, mc.player);
        state = State.WAITING_FOR_BOTTLES;
    }

    // Pitch down to ~87.5 degrees and throw the held item, then restore pitch.
    // The look packet is sent explicitly so the server registers the throw direction.
    private void throwBottle() {
        float yaw = mc.player.getYaw();
        float prevPitch = mc.player.getPitch();
        mc.player.setPitch(87.5f);
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, 87.5f, mc.player.isOnGround(), mc.player.horizontalCollision));
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        mc.player.setPitch(prevPitch);
    }

    // Walk declared fields and superclass fields to find the TextFieldWidget inside AnvilScreen.
    // Needed to update the visible text box value alongside handler.setNewItemName().
    private static TextFieldWidget getTextField(AnvilScreen screen) {
        Class<?> cls = screen.getClass();
        while (cls != null) {
            for (Field field : cls.getDeclaredFields()) {
                if (field.getType() == TextFieldWidget.class) {
                    field.setAccessible(true);
                    try {
                        return (TextFieldWidget) field.get(screen);
                    } catch (IllegalAccessException e) {
                        PawHax.LOG.error("Failed to get AnvilScreen text field", e);
                    }
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static boolean isShulker(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }
}
