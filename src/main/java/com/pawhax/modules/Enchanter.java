package com.pawhax.modules;

import com.pawhax.PawHax;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.AnvilBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class Enchanter extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> autoDetect = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-detect")
        .description("Auto-detect what to combine from your inventory: gear + the most common book, or - if no gear is present - the two most common distinct enchanted books, merged in the cheapest order.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Item> gearItem = sgGeneral.add(new ItemSetting.Builder()
        .name("gear")
        .description("The item type to enchant. Stays in the anvil and accumulates enchantments.")
        .defaultValue(Items.DIAMOND_CHESTPLATE)
        .visible(() -> !autoDetect.get())
        .build()
    );

    private final Setting<Item> bookItem = sgGeneral.add(new ItemSetting.Builder()
        .name("book")
        .description("The item type to sacrifice (usually an enchanted book). Consumed one at a time.")
        .defaultValue(Items.ENCHANTED_BOOK)
        .visible(() -> !autoDetect.get())
        .build()
    );

    private final Setting<Boolean> rename = sgGeneral.add(new BoolSetting.Builder()
        .name("rename")
        .description("Rename the gear at the same time as combining.")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> renameText = sgGeneral.add(new StringSetting.Builder()
        .name("rename-text")
        .description("The name to give the gear. Leave empty to reset to default name.")
        .defaultValue("")
        .visible(rename::get)
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

    private final Setting<Boolean> autoXP = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-xp")
        .description("Automatically throw XP bottles to afford combines.")
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

    private final Setting<Boolean> disableOnDone = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-on-done")
        .description("Automatically disable the module when no more items can be combined.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> closeOnDone = sgGeneral.add(new BoolSetting.Builder()
        .name("close-screen")
        .description("Automatically close the anvil screen when no more items can be combined.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pingOnDone = sgGeneral.add(new BoolSetting.Builder()
        .name("sound-ping")
        .description("Play a sound cue when no more items can be combined.")
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
        WAITING_FOR_GEAR,      // gear shift-clicked in, waiting for it to appear in slot 0
        WAITING_FOR_BOOK,      // book moved in, waiting for it to appear in slot 1
        WAITING_FOR_OUTPUT,    // output taken, waiting for slot 2 to clear
        WAITING_FOR_RENAME,    // rename text submitted, waiting for output slot to populate
        WAITING_FOR_BOTTLES    // bottle swapped into hand, waiting for it to appear in hotbar
    }

    private State state = State.IDLE;
    private int delay = 0;
    private boolean notified = false;

    // Rename - tracks whether the rename text has been submitted for the current input pair
    private boolean renameApplied = false;
    private TextFieldWidget cachedTextField = null;

    // Auto-detect runs once per activation and is cached - re-scanning the inventory every cycle
    // is unreliable, since by the time the second item of a pair is placed, the first one has
    // already left the inventory (it's sitting in the anvil), making it look like only one of the
    // two detected types is left.
    private boolean detected = false;
    private Item detectedGear = null;
    private Set<Identifier> detectedBookSignature = null;
    private boolean detectedBookMode = false;
    private Identifier detectedKeyA = null;
    private Identifier detectedKeyB = null;

    private enum ReopenState {
        IDLE,
        WATCHING_FOR_AIR,   // GUI closed, waiting to confirm the block went to air (anvil broke)
        WATCHING_FOR_ANVIL, // saw air at the pos, now waiting for a new anvil to land
        WAITING_TO_CLICK    // new anvil detected, waiting 250ms before clicking
    }

    private ReopenState reopenState = ReopenState.IDLE;
    private BlockPos watchedAnvilPos = null;
    private long reopenWatchDeadlineMs = -1;
    private long reopenClickAtMs = -1;

    public Enchanter() {
        super(PawHax.CATEGORY, "Enchanter", "Automatically combines enchanted items in an anvil (e.g. apply 12 Protection books to 12 chestplates).");
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
        notified = false;
        renameApplied = false;
        cachedTextField = null;
        // Detection resets here too (not just on activate/deactivate) so it re-runs fresh the
        // next time the anvil is opened, rather than being locked in from whenever the module
        // was last toggled on.
        resetDetection();
    }

    private void resetDetection() {
        detected = false;
        detectedGear = null;
        detectedBookSignature = null;
        detectedBookMode = false;
        detectedKeyA = null;
        detectedKeyB = null;
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
            default -> {}
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        boolean reopenEnabled = autoReopen.get();

        if (reopenEnabled) {
            handleReopenTick();
        }

        if (!(mc.currentScreen instanceof AnvilScreen)
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

        ItemStack gearSlot = handler.getSlot(AnvilScreenHandler.INPUT_1_ID).getStack();
        ItemStack bookSlot = handler.getSlot(AnvilScreenHandler.INPUT_2_ID).getStack();
        ItemStack output = handler.getSlot(AnvilScreenHandler.OUTPUT_ID).getStack();

        // Wait states - hold off until the server confirms the previous action
        switch (state) {
            case WAITING_FOR_GEAR -> {
                if (gearSlot.isEmpty()) return;
                state = State.IDLE;
                delay = 0;
                renameApplied = false;
                return;
            }
            case WAITING_FOR_BOOK -> {
                if (bookSlot.isEmpty()) return;
                state = State.IDLE;
                delay = 0;
                renameApplied = false;
                return;
            }
            case WAITING_FOR_OUTPUT -> {
                if (!output.isEmpty()) return;
                state = State.IDLE;
                delay = 0;
                renameApplied = false;
                return;
            }
            case WAITING_FOR_RENAME -> {
                if (output.isEmpty()) return;
                state = State.IDLE;
                delay = 0;
                return;
            }
            case WAITING_FOR_BOTTLES -> {
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
            default -> {}
        }

        delay++;
        if (delay <= clickDelay.get()) return;
        delay = 0;

        // Submit the rename text once both inputs are in, before taking the output
        if (rename.get() && !renameApplied && !gearSlot.isEmpty() && !bookSlot.isEmpty()) {
            String targetName = renameText.get();

            if (mc.currentScreen instanceof AnvilScreen anvilScreen) {
                if (cachedTextField == null) cachedTextField = getTextField(anvilScreen);
                if (cachedTextField != null) cachedTextField.setText(targetName);
            }
            handler.setNewItemName(targetName);
            renameApplied = true;
            state = State.WAITING_FOR_RENAME;
            return;
        }

        // Take a finished combine out of the anvil
        if (!output.isEmpty()) {
            int cost = handler.getLevelCost();
            int playerLevels = mc.player.experienceLevel;

            if (playerLevels < cost && !mc.player.isInCreativeMode()) {
                if (autoXP.get()) {
                    throwXPBottle(handler);
                    return;
                }
                stop("Not enough XP levels for the next combine (need " + cost + ", have " + playerLevels + ") - stopping.");
                return;
            }

            mc.interactionManager.clickSlot(handler.syncId, AnvilScreenHandler.OUTPUT_ID, 0, SlotActionType.QUICK_MOVE, mc.player);
            if (rename.get() && !renameText.get().isEmpty()) recordRecentName(renameText.get());
            state = State.WAITING_FOR_OUTPUT;
            return;
        }

        if (autoDetect.get() && !detected) {
            runAutoDetect(handler);
            if (!detected) return; // detection failed - runAutoDetect already called stop()
        }

        if (autoDetect.get() && detectedBookMode) {
            runBookMode(handler, gearSlot, bookSlot, detectedKeyA, detectedKeyB);
            return;
        }

        Item gear = autoDetect.get() ? detectedGear : gearItem.get();
        Item book = autoDetect.get() ? Items.ENCHANTED_BOOK : bookItem.get();

        // Only set when the book side must match an exact enchantment signature (auto-detect's
        // gear+book path) rather than "any enchanted book of this item type" (manual mode).
        Set<Identifier> bookSignature = autoDetect.get() ? detectedBookSignature : null;

        // Place gear first (left slot, shift-click lands in the first empty slot). If the book
        // slot already has stock, use it as the eligibility reference; otherwise peek at the
        // inventory for a candidate book without taking it yet.
        if (gearSlot.isEmpty()) {
            Set<Identifier> finalBookSignature = bookSignature;
            ItemStack bookReference;
            if (!bookSlot.isEmpty()) {
                bookReference = bookSlot;
            } else {
                int refSlot = findSlot(handler, book, stack -> matchesBookSignature(stack, finalBookSignature));
                if (refSlot == -1) {
                    stop("No more " + book.getName().getString() + " with enchantments found - stopping.");
                    return;
                }
                bookReference = handler.getSlot(refSlot).getStack();
            }

            ItemStack finalBookReference = bookReference;
            int slot = findSlot(handler, gear, stack -> gearNeedsBook(stack, finalBookReference));
            if (slot == -1) {
                stop("No more eligible " + gear.getName().getString() + " (already enchanted or out of stock) - stopping.");
                return;
            }

            mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);
            state = State.WAITING_FOR_GEAR;
            return;
        }

        // Then the book (right slot) - lands in the only remaining empty slot
        if (bookSlot.isEmpty()) {
            Set<Identifier> finalBookSignature = bookSignature;
            int slot = findSlot(handler, book, stack -> matchesBookSignature(stack, finalBookSignature));
            if (slot == -1) {
                stop("No more " + book.getName().getString() + " with enchantments found - stopping.");
                return;
            }

            mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);
            state = State.WAITING_FOR_BOOK;
            return;
        }

        // Both inputs present but no output - anvil refuses the combine (too expensive / incompatible)
        stop("Anvil can't combine these (too expensive or incompatible) - stopping.");
    }

    // Scans the inventory once and locks in what to combine for the rest of this run. Must not be
    // re-run mid-batch: once an item is placed in the anvil it leaves the inventory, so a live
    // re-scan would see fewer candidates and could wrongly conclude the pair disappeared.
    private void runAutoDetect(AnvilScreenHandler handler) {
        Map<Item, Integer> gearTally = tallyGear(handler);

        Map<Set<Identifier>, Integer> bookTally = tallyBookSignatures(handler);
        // Only single-enchantment books are merge candidates, so an already-merged book never
        // gets re-fed back in as one half of a fresh pair.
        Map<Set<Identifier>, Integer> singleEnchantTally = new HashMap<>();
        for (Map.Entry<Set<Identifier>, Integer> entry : bookTally.entrySet()) {
            if (entry.getKey().size() == 1) singleEnchantTally.put(entry.getKey(), entry.getValue());
        }

        // Armor maxes out at a stack size of 1, so "1 boots" is a completely normal, deliberate
        // gear+book combo, not noise - count alone can't tell stray clutter apart from intent.
        // Only prefer a book+book merge when there are genuinely 2+ distinct book types AND
        // whatever gear is present amounts to a single incidental piece (e.g. one elytra sitting
        // next to two different enchanted book stacks) - otherwise gear always wins.
        int bestGearCount = gearTally.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        boolean preferBookMode = singleEnchantTally.size() >= 2 && bestGearCount < 2;

        if (!preferBookMode && !gearTally.isEmpty()) {
            // Gear is present - standard gear + book workflow
            Pick<Item> gearPick = pickMostCommon(gearTally);
            if (gearPick.tie()) {
                stop("Enchanter: tie between gear types in your inventory - pick manually instead of using auto-detect.");
                return;
            }

            if (bookTally.isEmpty()) {
                stop("Enchanter: no enchanted books found in your inventory - stopping.");
                return;
            }

            Pick<Set<Identifier>> bookPick = pickMostCommon(bookTally);
            if (bookPick.tie()) {
                stop("Enchanter: tie between book types in your inventory - pick manually instead of using auto-detect.");
                return;
            }

            detectedGear = gearPick.item();
            detectedBookSignature = bookPick.item();
            detectedBookMode = false;
            detected = true;
            return;
        }

        if (singleEnchantTally.size() < 2) {
            stop("Enchanter: couldn't auto-detect a pair - need gear + book, or two distinct enchanted books, in your inventory - stopping.");
            return;
        }

        TwoPick<Set<Identifier>> twoPick = pickTopTwo(singleEnchantTally);
        if (twoPick.tie()) {
            stop("Enchanter: tie between book types in your inventory - can't decide which two to merge - stopping.");
            return;
        }

        detectedKeyA = twoPick.first().iterator().next();
        detectedKeyB = twoPick.second().iterator().next();
        detectedBookMode = true;
        detected = true;
    }

    // True if the stack is an enchanted book matching the required exact enchantment signature.
    // When requiredSignature is null (manual mode, no auto-detect), any enchanted book counts.
    private boolean matchesBookSignature(ItemStack stack, Set<Identifier> requiredSignature) {
        ItemEnchantmentsComponent ench = EnchantmentHelper.getEnchantments(stack);
        if (ench.isEmpty()) return false;
        if (requiredSignature == null) return true;
        return signatureOf(ench).equals(requiredSignature);
    }

    // The set of enchantment keys present on a stack, used to tell apart book "types" that all
    // share the same Item (Items.ENCHANTED_BOOK) but carry different enchantments.
    private Set<Identifier> signatureOf(ItemEnchantmentsComponent ench) {
        Set<Identifier> signature = new HashSet<>();
        for (RegistryEntry<Enchantment> entry : ench.getEnchantments()) {
            Identifier id = enchantmentId(entry);
            if (id != null) signature.add(id);
        }
        return signature;
    }

    // Resolves the registry id of an enchantment entry by looking the value up in the live
    // enchantment registry, rather than RegistryEntry#getKey() - the entry decoded onto an
    // ItemStack isn't always a registry-backed "reference" holder, so getKey() can silently
    // return empty even for perfectly normal vanilla enchantments. Looking the value up
    // directly in the registry is reliable regardless of which kind of holder we were handed.
    private Identifier enchantmentId(RegistryEntry<Enchantment> entry) {
        if (mc.world == null) return null;
        Registry<Enchantment> registry = mc.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        return registry.getId(entry.value());
    }

    // Tally enchanted books by their exact enchantment signature (not by Item - every enchanted
    // book is Items.ENCHANTED_BOOK regardless of what's on it, so grouping by Item can't tell a
    // Protection book apart from a Mending book).
    private Map<Set<Identifier>, Integer> tallyBookSignatures(AnvilScreenHandler handler) {
        Map<Set<Identifier>, Integer> tally = new HashMap<>();
        for (int i = 3; i < 39; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty() || stack.getItem() != Items.ENCHANTED_BOOK) continue;

            ItemEnchantmentsComponent ench = EnchantmentHelper.getEnchantments(stack);
            if (ench.isEmpty()) continue;

            Set<Identifier> signature = signatureOf(ench);
            if (signature.isEmpty()) continue;

            tally.merge(signature, stack.getCount(), Integer::sum);
        }
        return tally;
    }

    // Tally inventory items that are genuine enchantable equipment (tools/armor/weapons, not books).
    // Uses the raw ENCHANTABLE component check (not ItemStack.isEnchantable(), which also requires
    // the item to be currently unenchanted - wrong here, since gear may already carry one
    // enchantment and still need another) and not EnchantmentHelper.canHaveEnchantments(), which
    // just checks for a present ENCHANTMENTS component and matches far too many non-equipment items
    // (food, rockets, bottles, etc.) in this version.
    private Map<Item, Integer> tallyGear(AnvilScreenHandler handler) {
        Map<Item, Integer> tally = new HashMap<>();
        for (int i = 3; i < 39; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            if (stack.get(DataComponentTypes.STORED_ENCHANTMENTS) != null) continue;
            if (!stack.contains(DataComponentTypes.ENCHANTABLE)) continue;

            tally.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return tally;
    }

    private record Pick<T>(T item, boolean tie) {}

    // Picks the highest-tallied entry; flags a tie if two or more share the top count
    private <T> Pick<T> pickMostCommon(Map<T, Integer> tally) {
        T best = null;
        int bestCount = -1;
        boolean tie = false;

        for (Map.Entry<T, Integer> entry : tally.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                best = entry.getKey();
                tie = false;
            } else if (entry.getValue() == bestCount) {
                tie = true;
            }
        }

        return new Pick<>(best, tie);
    }

    private record TwoPick<T>(T first, T second, boolean tie) {}

    // Picks the two highest-tallied entries (regardless of which of the two has more). Flags a
    // tie only if the boundary between 2nd and 3rd place is ambiguous - i.e. it's unclear which
    // entry should be the "second" one. Caller must ensure tally.size() >= 2.
    private <T> TwoPick<T> pickTopTwo(Map<T, Integer> tally) {
        List<Map.Entry<T, Integer>> sorted = new ArrayList<>(tally.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());

        boolean tie = sorted.size() > 2 && sorted.get(2).getValue().equals(sorted.get(1).getValue());
        return new TwoPick<>(sorted.get(0).getKey(), sorted.get(1).getKey(), tie);
    }

    // True if combining would actually add or upgrade an enchantment on the gear
    private boolean gearNeedsBook(ItemStack gear, ItemStack book) {
        ItemEnchantmentsComponent bookEnch = EnchantmentHelper.getEnchantments(book);
        if (bookEnch.isEmpty()) return false;

        ItemEnchantmentsComponent gearEnch = EnchantmentHelper.getEnchantments(gear);
        for (RegistryEntry<Enchantment> enchantment : bookEnch.getEnchantments()) {
            if (gearEnch.getLevel(enchantment) < bookEnch.getLevel(enchantment)) return true;
        }

        return false;
    }

    private record BookCandidate(int slot, ItemStack stack) {}

    // Finds an enchanted book carrying `has` but not `notHas` - used to find a fresh single-purpose
    // book for one side of a book+book merge without accidentally re-feeding an already-merged book.
    private BookCandidate findBookFor(AnvilScreenHandler handler, Identifier has, Identifier notHas) {
        for (int i = 3; i < 39; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty() || stack.getItem() != Items.ENCHANTED_BOOK) continue;

            ItemEnchantmentsComponent ench = EnchantmentHelper.getEnchantments(stack);
            boolean hasTarget = false, hasOther = false;
            for (RegistryEntry<Enchantment> entry : ench.getEnchantments()) {
                Identifier id = enchantmentId(entry);
                if (has.equals(id)) hasTarget = true;
                if (notHas.equals(id)) hasOther = true;
            }

            if (hasTarget && !hasOther) return new BookCandidate(i, stack);
        }
        return null;
    }

    // The exact level cost the anvil charges for this stack's enchantments if used as the
    // "addition" (right slot, INPUT_2). Replicates AnvilScreenHandler#updateResult verbatim
    // (verified via bytecode disassembly): each enchantment costs max(1, anvilCost / 2) * level
    // when the addition is an enchanted book (the /2 halving only applies to the addition side).
    // The base (left slot, INPUT_1) keeps its own enchantments for free - only the addition's
    // enchantments are charged. Reads Enchantment.getAnvilCost() live from the registry, so this
    // tracks datapack/version changes instead of hardcoding vanilla's numbers.
    private int additionCost(ItemStack book) {
        int total = 0;
        ItemEnchantmentsComponent ench = EnchantmentHelper.getEnchantments(book);
        for (RegistryEntry<Enchantment> entry : ench.getEnchantments()) {
            int level = ench.getLevel(entry);
            int anvilCost = Math.max(1, entry.value().getAnvilCost() / 2);
            total += anvilCost * level;
        }
        return total;
    }

    // Merges two enchanted books into one. The cheaper-to-apply enchantment (lower additionCost)
    // is always placed as the addition (right slot) since only the addition's enchantments are
    // charged XP - the base (left slot) keeps its enchantment for free. This is worked out
    // analytically from registry data, no need to test both orders in the anvil itself.
    private void runBookMode(AnvilScreenHandler handler, ItemStack baseSlot, ItemStack additionSlot, Identifier keyA, Identifier keyB) {
        // Both slots empty - decide which enchantment goes in as base (free) vs addition (charged)
        // and place the base first. Decided fresh each time a new pair starts.
        if (baseSlot.isEmpty()) {
            BookCandidate a = findBookFor(handler, keyA, keyB);
            BookCandidate b = findBookFor(handler, keyB, keyA);

            if (a == null || b == null) {
                stop("Enchanter: no more books to merge for the selected enchantments - stopping.");
                return;
            }

            BookCandidate base = additionCost(a.stack()) >= additionCost(b.stack()) ? a : b;
            mc.interactionManager.clickSlot(handler.syncId, base.slot(), 0, SlotActionType.QUICK_MOVE, mc.player);
            state = State.WAITING_FOR_GEAR;
            return;
        }

        // Base is already placed - the addition must carry whichever of the two enchantments
        // the base doesn't already have.
        if (additionSlot.isEmpty()) {
            ItemEnchantmentsComponent baseEnch = EnchantmentHelper.getEnchantments(baseSlot);
            boolean baseHasA = false;
            for (RegistryEntry<Enchantment> entry : baseEnch.getEnchantments()) {
                if (keyA.equals(enchantmentId(entry))) baseHasA = true;
            }

            Identifier additionKey = baseHasA ? keyB : keyA;
            Identifier excludeKey = baseHasA ? keyA : keyB;

            BookCandidate addition = findBookFor(handler, additionKey, excludeKey);
            if (addition == null) {
                stop("Enchanter: no more books to merge for the selected enchantments - stopping.");
                return;
            }

            mc.interactionManager.clickSlot(handler.syncId, addition.slot(), 0, SlotActionType.QUICK_MOVE, mc.player);
            state = State.WAITING_FOR_BOOK;
            return;
        }

        stop("Anvil can't combine these books (too expensive or incompatible) - stopping.");
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

    // Throw an XP bottle to gain levels for the combine cost.
    // Uses a SWAP click to move the bottle into the currently selected hotbar slot
    // rather than InvUtils.swap(), which would close the anvil screen.
    private void throwXPBottle(AnvilScreenHandler handler) {
        if (mc.player == null || mc.interactionManager == null) return;

        int selectedSlot = mc.player.getInventory().getSelectedSlot();

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
        if (bottleSlot == -1) {
            stop("Enchanter: out of XP bottles - stopping.");
            return;
        }

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

    // Push the used name to the front of the recent list, cap at 10 entries
    private void recordRecentName(String name) {
        List<String> recent = new ArrayList<>(recentNames.get());
        recent.remove(name);
        recent.add(0, name);
        if (recent.size() > 10) recent.subList(10, recent.size()).clear();
        recentNames.set(recent);
    }

    private int findSlot(AnvilScreenHandler handler, Item item, Predicate<ItemStack> extra) {
        for (int i = 3; i < 39; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            if (stack.getItem() != item) continue;
            if (extra != null && !extra.test(stack)) continue;
            return i;
        }
        return -1;
    }

    private void stop(String reason) {
        if (notified) return;
        notified = true;

        if (disableOnDone.get()) toggle();
        if (mc.player == null) return;
        if (closeOnDone.get()) mc.player.closeHandledScreen();
        if (pingOnDone.get()) mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        info(reason);
    }
}
