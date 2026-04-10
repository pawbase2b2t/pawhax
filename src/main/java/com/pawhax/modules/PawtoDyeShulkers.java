package com.pawhax.modules;

import net.minecraft.item.Item;
import net.minecraft.item.Items;
import com.pawhax.PawHax;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.text.Text;
import net.minecraft.item.DyeItem;
import net.minecraft.util.DyeColor;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.TextColor;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.screen.PlayerScreenHandler;
import meteordevelopment.meteorclient.settings.*;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.AbstractRecipeScreenHandler;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;

public class PawtoDyeShulkers extends Module {
    public PawtoDyeShulkers() { super(PawHax.CATEGORY, "PawtoDyeShulkers", "dyes shulks 4 u"); }

    public enum OperatingMode { Table, Inventory, Both }

    private enum State {
        IDLE,
        WAITING_FOR_OUTPUT,  // placed dye+shulker, output slot not yet populated
        WAITING_FOR_CLEAR    // took output, output slot not yet empty
    }

    private final Setting<OperatingMode> operatingMode = settings.getDefaultGroup().add(
        new EnumSetting.Builder<OperatingMode>()
            .name("operating-mode")
            .description("Whether to dye in crafting tables, the inventory grid, or both.")
            .defaultValue(OperatingMode.Both)
            .build()
    );
    private final Setting<Boolean> autoDetectColor = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("auto-detect-color")
            .description("Use whichever dye color you have the most of in your inventory.")
            .defaultValue(false)
            .build()
    );
    private final Setting<DyeColor> dyeColor = settings.getDefaultGroup().add(
        new EnumSetting.Builder<DyeColor>()
            .name("color")
            .defaultValue(DyeColor.LIGHT_BLUE)
            .visible(() -> !autoDetectColor.get())
            .build()
    );
    private final Setting<Boolean> reDyeColored = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("recolor-colored")
            .description("Re-color shulker boxes which already have a different dye color applied.")
            .defaultValue(false)
            .build()
    );
    private final Setting<Boolean> closeOnDone = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("close-screen")
            .description("Automatically close the screen when no more shulkers can be dyed.")
            .defaultValue(true)
            .build()
    );
    private final Setting<Boolean> disableOnDone = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("disable-on-done")
            .description("Automatically disable the module when no more shulkers can be dyed.")
            .defaultValue(false)
            .build()
    );
    private final Setting<Boolean> pingOnDone = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("sound-ping")
            .description("Play a sound cue when no more shulkers can be dyed.")
            .defaultValue(true)
            .build()
    );
    private final Setting<Integer> clickDelay = settings.getDefaultGroup().add(
        new IntSetting.Builder()
            .name("click-delay")
            .description("Ticks to wait between actions.")
            .range(1, 100).sliderRange(2, 20)
            .defaultValue(5)
            .build()
    );

    // Cache DyeColor.values() to avoid allocating a new array on every auto-detect call
    private static final DyeColor[] DYE_COLORS = DyeColor.values();

    private static final TextColor DARK_GRAY = TextColor.fromRgb(0x555555);
    private static final TextColor LIGHT_GRAY = TextColor.fromRgb(0xAAAAAA);

    private State state = State.IDLE;
    private int timer = 0;
    private boolean notified = false;
    private DyeColor lastColor = null; // track color changes so notified resets correctly

    // True if the item is any coloured shulker box
    private static boolean isColoredShulker(Item box) {
        return box == Items.BLACK_SHULKER_BOX || box == Items.GRAY_SHULKER_BOX
            || box == Items.LIGHT_GRAY_SHULKER_BOX || box == Items.WHITE_SHULKER_BOX
            || box == Items.RED_SHULKER_BOX || box == Items.ORANGE_SHULKER_BOX
            || box == Items.YELLOW_SHULKER_BOX || box == Items.LIME_SHULKER_BOX
            || box == Items.GREEN_SHULKER_BOX || box == Items.CYAN_SHULKER_BOX
            || box == Items.LIGHT_BLUE_SHULKER_BOX || box == Items.BLUE_SHULKER_BOX
            || box == Items.PURPLE_SHULKER_BOX || box == Items.MAGENTA_SHULKER_BOX
            || box == Items.PINK_SHULKER_BOX || box == Items.BROWN_SHULKER_BOX;
    }

    // True if the item is the target-colour shulker box
    private static boolean isTargetShulker(Item box, DyeColor color) {
        return box == ShulkerBoxBlock.get(color).asItem();
    }

    // True if this shulker still needs dyeing (not already the target color, and passes recolor check)
    private static boolean isValidShulker(Item box, DyeColor color, boolean recolor) {
        if (isTargetShulker(box, color)) return false;
        if (box == Items.SHULKER_BOX) return true;
        return recolor && isColoredShulker(box);
    }

    // Find the first inventory slot containing a dyeable shulker
    private <T extends AbstractRecipeScreenHandler> int findShulker(T cs, int invStart, int invEnd, DyeColor color, boolean recolor) {
        for (int n = invStart; n < invEnd; n++) {
            if (isValidShulker(cs.getSlot(n).getStack().getItem(), color, recolor)) return n;
        }
        return -1;
    }

    // Find the first empty grid input slot (slots 1..inputEnd-1)
    private int findEmptyInputSlot(AbstractRecipeScreenHandler cs, int inputEnd) {
        for (int n = 1; n < inputEnd; n++) {
            if (cs.getSlot(n).getStack().isEmpty()) return n;
        }
        return -1;
    }

    private void onFinished(String msg) {
        if (notified) return;
        notified = true;
        if (disableOnDone.get()) toggle();
        if (mc.player == null) return;
        if (closeOnDone.get()) mc.player.closeHandledScreen();
        if (pingOnDone.get()) mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        mc.player.sendMessage(pawHaxMessage("PawtoDyeShulkers", msg), false);
    }

    // Scan inventory and grid slots for dye, return the color with the highest total stack count
    private <T extends AbstractRecipeScreenHandler> DyeColor detectDominantDye(T cs, int inputEnd, int invStart, int invEnd) {
        int[] counts = new int[DYE_COLORS.length];
        for (int n = invStart; n < invEnd; n++) {
            ItemStack stack = cs.getSlot(n).getStack();
            if (stack.getItem() instanceof DyeItem dye) counts[dye.getColor().getId()] += stack.getCount();
        }
        // Also check grid input slots in case dye was already moved there
        for (int n = 1; n < inputEnd; n++) {
            ItemStack stack = cs.getSlot(n).getStack();
            if (stack.getItem() instanceof DyeItem dye) counts[dye.getColor().getId()] += stack.getCount();
        }
        DyeColor best = null;
        int bestCount = 0;
        for (DyeColor c : DYE_COLORS) {
            if (counts[c.getId()] > bestCount) { bestCount = counts[c.getId()]; best = c; }
        }
        return best != null ? best : dyeColor.get(); // fall back to manual setting if no dye found
    }

    private <T extends AbstractRecipeScreenHandler> void tick(T cs, int inputEnd, int invStart, int invEnd) {
        DyeColor color = autoDetectColor.get() ? detectDominantDye(cs, inputEnd, invStart, invEnd) : dyeColor.get();
        Item dyeItem = DyeItem.byColor(color);
        boolean recolor = reDyeColored.get();

        // If the active color changed (auto-detect picked a different dye, or user changed the
        // manual setting), reset notified so we don't silently skip the new batch
        if (color != lastColor) { lastColor = color; notified = false; }

        ItemStack output = cs.getSlot(0).getStack();

        // Take the output if it's a freshly dyed shulker
        if (!output.isEmpty() && isTargetShulker(output.getItem(), color)) {
            InvUtils.shiftClick().slotId(0);
            state = State.WAITING_FOR_CLEAR;
            return;
        }

        // Scan the grid - keep one dye and one shulker, push everything else back
        int dyeSlotInGrid = -1;
        int shulkSlotInGrid = -1;

        for (int n = 1; n < inputEnd; n++) {
            ItemStack gridStack = cs.getSlot(n).getStack();
            if (gridStack.isEmpty()) continue;
            Item item = gridStack.getItem();
            if (item == dyeItem && dyeSlotInGrid == -1) {
                dyeSlotInGrid = n;
            } else if (isValidShulker(item, color, recolor) && shulkSlotInGrid == -1) {
                shulkSlotInGrid = n;
            } else {
                // Extra or irrelevant item - push back to inventory
                InvUtils.shiftClick().slotId(n);
            }
        }

        // Move dye into grid if missing - shulker will be placed next tick
        if (dyeSlotInGrid == -1) {
            int slot = -1;
            for (int n = invStart; n < invEnd; n++) {
                if (cs.getSlot(n).getStack().getItem() == dyeItem) { slot = n; break; }
            }
            if (slot == -1) { onFinished("No dye found, finished!"); return; }
            int target = findEmptyInputSlot(cs, inputEnd);
            if (target == -1) target = 1;
            InvUtils.move().fromId(slot).toId(target);
            return;
        }

        // Move shulker into grid if missing - both items are now placed, wait for output
        if (shulkSlotInGrid == -1) {
            int slot = findShulker(cs, invStart, invEnd, color, recolor);
            if (slot == -1) { onFinished("Finished dyeing shulkers!"); return; }
            int target = findEmptyInputSlot(cs, inputEnd);
            if (target == -1) target = dyeSlotInGrid == 1 ? 2 : 1;
            InvUtils.move().fromId(slot).toId(target);
            state = State.WAITING_FOR_OUTPUT;
        }
    }

    private static MutableText pawHaxMessage(String moduleName, String message) {
        TextColor themeColor = TextColor.fromRgb(MeteorClient.ADDON.color.getPacked());
        return Text.empty()
            .append(Text.literal("[").setStyle(Style.EMPTY.withColor(DARK_GRAY)))
            .append(Text.literal(moduleName).setStyle(Style.EMPTY.withColor(themeColor)))
            .append(Text.literal("] ").setStyle(Style.EMPTY.withColor(DARK_GRAY)))
            .append(Text.literal(message).setStyle(Style.EMPTY.withColor(LIGHT_GRAY).withItalic(true)));
    }

    @Override
    public void onDeactivate() {
        state = State.IDLE;
        timer = 0;
        notified = false;
        lastColor = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.currentScreen == null) return;

        if (mc.currentScreen instanceof CraftingScreen
            && mc.player.currentScreenHandler instanceof CraftingScreenHandler cs) {
            if (operatingMode.get() == OperatingMode.Inventory) return;

            // Slot layout for crafting table: 0=output, 1-9=grid inputs, 10-45=player inventory
            ItemStack output = cs.getSlot(0).getStack();
            if (state == State.WAITING_FOR_OUTPUT) {
                if (output.isEmpty()) return;
                state = State.IDLE; timer = 0; return;
            }
            if (state == State.WAITING_FOR_CLEAR) {
                if (!output.isEmpty()) return;
                state = State.IDLE; timer = 0; return;
            }

            if (++timer < clickDelay.get()) return;
            timer = 0;
            tick(cs, 10, 10, 46);

        } else if (mc.currentScreen instanceof InventoryScreen
            && mc.player.currentScreenHandler instanceof PlayerScreenHandler ps) {
            if (operatingMode.get() == OperatingMode.Table) return;

            // Slot layout for inventory grid: 0=output, 1-4=grid inputs, 5-8=armor, 9-44=player inventory
            ItemStack output = ps.getSlot(0).getStack();
            if (state == State.WAITING_FOR_OUTPUT) {
                if (output.isEmpty()) return;
                state = State.IDLE; timer = 0; return;
            }
            if (state == State.WAITING_FOR_CLEAR) {
                if (!output.isEmpty()) return;
                state = State.IDLE; timer = 0; return;
            }

            if (++timer < clickDelay.get()) return;
            timer = 0;
            tick(ps, 5, 9, 45);

        } else {
            // Screen closed or changed - reset so we start clean next time
            state = State.IDLE;
            timer = 0;
            notified = false;
            lastColor = null;
        }
    }
}
