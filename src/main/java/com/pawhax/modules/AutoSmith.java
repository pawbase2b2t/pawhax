package com.pawhax.modules;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.ArrayDeque;
import net.minecraft.item.*;
import com.pawhax.PawHax;
import net.minecraft.sound.SoundEvents;
import org.jetbrains.annotations.Nullable;
import meteordevelopment.orbit.EventHandler;
import java.util.concurrent.ThreadLocalRandom;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.screen.slot.SlotActionType;
import meteordevelopment.meteorclient.settings.*;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.equipment.ArmorMaterial;
import net.minecraft.item.equipment.EquipmentType;
import net.minecraft.screen.SmithingScreenHandler;
import net.minecraft.screen.sync.ItemStackHash;
import net.minecraft.screen.sync.ComponentChangesHash;
import net.minecraft.item.equipment.trim.ArmorTrim;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.client.gui.screen.ingame.SmithingScreen;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.events.world.TickEvent;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * ported from stardust (github.com/0xtas/stardust) for pawhax
 * Automatically upgrades gear or trims armor in smithing tables.
 **/
public class AutoSmith extends Module {
    public AutoSmith() { super(PawHax.CATEGORY, "AutoSmith", "Automatically upgrade gear or trim armor in smithing tables. (ported from Stardust + features added) *you MUST you a viaversion above 1.21.5 for this to work!*"); }

    private final SettingGroup trimSettings = settings.createGroup("trim armour");
    private final SettingGroup modeSettings = settings.createGroup("Smithing Mode");

    public enum ModuleMode {
        Packet, Interact
    }
    public enum SmithingMode {
        Trim, Upgrade
    }
    public enum ArmorMaterials {
        Iron, Gold, Chain, Turtle, Leather, Diamond, Netherite;
        public boolean materialEquals(ArmorMaterial material) {
            return switch (this) {
                case Iron -> material == net.minecraft.item.equipment.ArmorMaterials.IRON;
                case Gold -> material == net.minecraft.item.equipment.ArmorMaterials.GOLD;
                case Chain -> material == net.minecraft.item.equipment.ArmorMaterials.CHAIN;
                case Turtle -> material == net.minecraft.item.equipment.ArmorMaterials.TURTLE_SCUTE;
                case Leather -> material == net.minecraft.item.equipment.ArmorMaterials.LEATHER;
                case Diamond -> material == net.minecraft.item.equipment.ArmorMaterials.DIAMOND;
                case Netherite -> material == net.minecraft.item.equipment.ArmorMaterials.NETHERITE;
            };
        }
    }
    public enum ArmorTrims {
        Eye("minecraft:eye"),
        Vex("minecraft:vex"),
        Rib("minecraft:rib"),
        Bolt("minecraft:bolt"),
        Wild("minecraft:wild"),
        Dune("minecraft:dune"),
        Host("minecraft:host"),
        Ward("minecraft:ward"),
        Tide("minecraft:tide"),
        Flow("minecraft:flow"),
        Coast("minecraft:coast"),
        Snout("minecraft:snout"),
        Spire("minecraft:spire"),
        Raiser("minecraft:raiser"),
        Shaper("minecraft:shaper"),
        Sentry("minecraft:sentry"),
        Silence("minecraft:silence"),
        Wayfinder("minecraft:wayfinder");

        public final String label;

        ArmorTrims(String label) { this.label = label; }
    }
    public enum TrimMaterial {
        Iron("minecraft:iron"),
        Gold("minecraft:gold"),
        Lapis("minecraft:lapis"),
        Resin("minecraft:resin"),
        Copper("minecraft:copper"),
        Quartz("minecraft:quartz"),
        Emerald("minecraft:emerald"),
        Diamond("minecraft:diamond"),
        Redstone("minecraft:redstone"),
        Amethyst("minecraft:amethyst"),
        Netherite("minecraft:netherite");

        public final String label;

        TrimMaterial(String label) { this.label = label; }
    }

    private final Setting<ModuleMode> moduleMode = modeSettings.add(
        new EnumSetting.Builder<ModuleMode>()
            .name("module-mode")
            .description("Packet is significantly faster, but may get you kicked in some scenarios.")
            .defaultValue(ModuleMode.Packet)
            .build()
    );
    private final Setting<Integer> tickRate = modeSettings.add(
        new IntSetting.Builder()
            .name("tick-delay")
            .description("Increase this if the server is kicking you.")
            .visible(() -> moduleMode.get().equals(ModuleMode.Interact))
            .range(2, 100)
            .sliderRange(2, 20)
            .defaultValue(4)
            .build()
    );
    private final Setting<Integer> packetLimit = modeSettings.add(
        new IntSetting.Builder()
            .name("packet-limit")
            .description("Decrease this if the server is kicking you.")
            .visible(() -> moduleMode.get().equals(ModuleMode.Packet))
            .min(20).sliderMax(100)
            .defaultValue(57)
            .build()
    );
    private final Setting<SmithingMode> operatingMode = modeSettings.add(
        new EnumSetting.Builder<SmithingMode>()
            .name("smithing-mode")
            .defaultValue(SmithingMode.Upgrade)
            .build()
    );
    private final Setting<Boolean> overwriteTrims = modeSettings.add(
        new BoolSetting.Builder()
            .name("overwrite-trims")
            .description("Trim armor pieces which already contain a different trim pattern or material.")
            .defaultValue(false)
            .visible(() -> operatingMode.get() == SmithingMode.Trim)
            .build()
    );
    private final Setting<Boolean> autoDetect = modeSettings.add(
        new BoolSetting.Builder()
            .name("auto-detect")
            .description("Detect the most common armor material, trim pattern, and trim material in your inventory and use those instead of the manual settings below.")
            .defaultValue(true)
            .visible(() -> operatingMode.get() == SmithingMode.Trim)
            .build()
    );

    private final Setting<ArmorMaterials> helmetType = trimSettings.add(
        new EnumSetting.Builder<ArmorMaterials>()
            .name("helmet-armor-type")
            .description("Which type of helmet to apply trims to.")
            .defaultValue(ArmorMaterials.Netherite)
            .visible(() -> operatingMode.get() == SmithingMode.Trim && !autoDetect.get())
            .build()
    );
    private final Setting<ArmorTrims> helmetTrim = trimSettings.add(
        new EnumSetting.Builder<ArmorTrims>()
            .name("helmet-armor-trim")
            .description("Which armor trim to apply onto helmets.")
            .defaultValue(ArmorTrims.Eye)
            .visible(() -> operatingMode.get() == SmithingMode.Trim && !autoDetect.get())
            .build()
    );
    private final Setting<TrimMaterial> helmetTrimMaterial = trimSettings.add(
        new EnumSetting.Builder<TrimMaterial>()
            .name("helmet-trim-material")
            .description("What material to use for helmet armor trims.")
            .defaultValue(TrimMaterial.Amethyst)
            .visible(() -> operatingMode.get() == SmithingMode.Trim && !autoDetect.get())
            .build()
    );

    private final Setting<ArmorMaterials> chestplateType = trimSettings.add(
        new EnumSetting.Builder<ArmorMaterials>()
            .name("chestplate-armor-type")
            .description("Which type of chestplates to apply trims to.")
            .defaultValue(ArmorMaterials.Netherite)
            .visible(() -> operatingMode.get() == SmithingMode.Trim && !autoDetect.get())
            .build()
    );
    private final Setting<ArmorTrims> chestplateTrim = trimSettings.add(
        new EnumSetting.Builder<ArmorTrims>()
            .name("chestplate-armor-trim")
            .description("Which armor trim to apply onto chestplates.")
            .defaultValue(ArmorTrims.Eye)
            .visible(() -> operatingMode.get() == SmithingMode.Trim && !autoDetect.get())
            .build()
    );
    private final Setting<TrimMaterial> chestplateTrimMaterial = trimSettings.add(
        new EnumSetting.Builder<TrimMaterial>()
            .name("chestplate-trim-material")
            .description("What material to use for chestplate armor trims.")
            .defaultValue(TrimMaterial.Amethyst)
            .visible(() -> operatingMode.get() == SmithingMode.Trim && !autoDetect.get())
            .build()
    );

    private final Setting<ArmorMaterials> leggingsType = trimSettings.add(
        new EnumSetting.Builder<ArmorMaterials>()
            .name("leggings-armor-type")
            .description("Which type of leggings to apply trims to.")
            .defaultValue(ArmorMaterials.Netherite)
            .visible(() -> operatingMode.get() == SmithingMode.Trim && !autoDetect.get())
            .build()
    );
    private final Setting<ArmorTrims> leggingsTrim = trimSettings.add(
        new EnumSetting.Builder<ArmorTrims>()
            .name("leggings-armor-trim")
            .description("Which armor trim to apply onto leggings.")
            .defaultValue(ArmorTrims.Eye)
            .visible(() -> operatingMode.get() == SmithingMode.Trim && !autoDetect.get())
            .build()
    );
    private final Setting<TrimMaterial> leggingsTrimMaterial = trimSettings.add(
        new EnumSetting.Builder<TrimMaterial>()
            .name("leggings-trim-material")
            .description("What material to use for leggings armor trims.")
            .defaultValue(TrimMaterial.Amethyst)
            .visible(() -> operatingMode.get() == SmithingMode.Trim && !autoDetect.get())
            .build()
    );

    private final Setting<ArmorMaterials> bootsType = trimSettings.add(
        new EnumSetting.Builder<ArmorMaterials>()
            .name("boots-armor-type")
            .description("Which type of boots to apply trims to.")
            .defaultValue(ArmorMaterials.Netherite)
            .visible(() -> operatingMode.get() == SmithingMode.Trim && !autoDetect.get())
            .build()
    );
    private final Setting<ArmorTrims> bootsTrim = trimSettings.add(
        new EnumSetting.Builder<ArmorTrims>()
            .name("boots-armor-trim")
            .description("Which armor trim to apply onto boots.")
            .defaultValue(ArmorTrims.Eye)
            .visible(() -> operatingMode.get() == SmithingMode.Trim && !autoDetect.get())
            .build()
    );
    private final Setting<TrimMaterial> bootsTrimMaterial = trimSettings.add(
        new EnumSetting.Builder<TrimMaterial>()
            .name("boots-trim-material")
            .description("What material to use for boots armor trims.")
            .defaultValue(TrimMaterial.Amethyst)
            .visible(() -> operatingMode.get() == SmithingMode.Trim && !autoDetect.get())
            .build()
    );

    private final Setting<Boolean> closeOnDone = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("close-screen")
            .description("Automatically close the crafting screen when no more gear can be upgraded.")
            .defaultValue(true)
            .build()
    );
    private final Setting<Boolean> disableOnDone = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("disable-on-done")
            .description("Automatically disable the module when no more gear can be upgraded.")
            .defaultValue(false)
            .build()
    );
    private final Setting<Boolean> pingOnDone = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("sound-ping")
            .description("Play a sound cue when no more gear can be trimmed or upgraded.")
            .defaultValue(true)
            .build()
    );
    private final Setting<Double> pingVolume = settings.getDefaultGroup().add(
        new DoubleSetting.Builder()
            .name("ping-volume")
            .visible(pingOnDone::get)
            .sliderMin(0.0)
            .sliderMax(5.0)
            .defaultValue(0.5)
            .build()
    );
    private final Setting<Boolean> debug = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("debug")
            .description("Displays debug messages in your chat.")
            .defaultValue(false)
            .build()
    );

    private int timer = 0;
    private boolean notified = false;
    private boolean foundEquip = false;
    private boolean foundIngots = false;
    private boolean foundTemplates = false;
    private boolean resettingTemplates = false;
    private boolean resettingMaterials = false;
    private @Nullable ItemStack trimStack = null;
    private @Nullable ItemStack materialStack = null;
    private @Nullable ItemStack equipmentStack = null;
    private @Nullable EquipmentType currentlyLookingFor = null;
    private final IntArrayList projectedEmpty = new IntArrayList();
    private final IntArrayList processedSlots = new IntArrayList();
    private final List<EquipmentType> exhaustedArmorTypes = new ArrayList<>();

    private boolean autoDetectRan = false;
    private @Nullable ArmorMaterials detectedArmorMaterial = null;
    private @Nullable ArmorTrims detectedTrimPattern = null;
    private @Nullable TrimMaterial detectedTrimMaterial = null;

    private static final Map<Item, ArmorTrims> TEMPLATE_TO_TRIM = new HashMap<>();
    private static final Map<Item, TrimMaterial> INGREDIENT_TO_MATERIAL = new HashMap<>();
    static {
        TEMPLATE_TO_TRIM.put(Items.EYE_ARMOR_TRIM_SMITHING_TEMPLATE, ArmorTrims.Eye);
        TEMPLATE_TO_TRIM.put(Items.VEX_ARMOR_TRIM_SMITHING_TEMPLATE, ArmorTrims.Vex);
        TEMPLATE_TO_TRIM.put(Items.RIB_ARMOR_TRIM_SMITHING_TEMPLATE, ArmorTrims.Rib);
        TEMPLATE_TO_TRIM.put(Items.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE, ArmorTrims.Bolt);
        TEMPLATE_TO_TRIM.put(Items.WILD_ARMOR_TRIM_SMITHING_TEMPLATE, ArmorTrims.Wild);
        TEMPLATE_TO_TRIM.put(Items.DUNE_ARMOR_TRIM_SMITHING_TEMPLATE, ArmorTrims.Dune);
        TEMPLATE_TO_TRIM.put(Items.HOST_ARMOR_TRIM_SMITHING_TEMPLATE, ArmorTrims.Host);
        TEMPLATE_TO_TRIM.put(Items.WARD_ARMOR_TRIM_SMITHING_TEMPLATE, ArmorTrims.Ward);
        TEMPLATE_TO_TRIM.put(Items.TIDE_ARMOR_TRIM_SMITHING_TEMPLATE, ArmorTrims.Tide);
        TEMPLATE_TO_TRIM.put(Items.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE, ArmorTrims.Flow);
        TEMPLATE_TO_TRIM.put(Items.COAST_ARMOR_TRIM_SMITHING_TEMPLATE, ArmorTrims.Coast);
        TEMPLATE_TO_TRIM.put(Items.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE, ArmorTrims.Snout);
        TEMPLATE_TO_TRIM.put(Items.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE, ArmorTrims.Spire);
        TEMPLATE_TO_TRIM.put(Items.RAISER_ARMOR_TRIM_SMITHING_TEMPLATE, ArmorTrims.Raiser);
        TEMPLATE_TO_TRIM.put(Items.SHAPER_ARMOR_TRIM_SMITHING_TEMPLATE, ArmorTrims.Shaper);
        TEMPLATE_TO_TRIM.put(Items.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE, ArmorTrims.Sentry);
        TEMPLATE_TO_TRIM.put(Items.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE, ArmorTrims.Silence);
        TEMPLATE_TO_TRIM.put(Items.WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE, ArmorTrims.Wayfinder);

        INGREDIENT_TO_MATERIAL.put(Items.IRON_INGOT, TrimMaterial.Iron);
        INGREDIENT_TO_MATERIAL.put(Items.GOLD_INGOT, TrimMaterial.Gold);
        INGREDIENT_TO_MATERIAL.put(Items.LAPIS_LAZULI, TrimMaterial.Lapis);
        INGREDIENT_TO_MATERIAL.put(Items.RESIN_BRICK, TrimMaterial.Resin);
        INGREDIENT_TO_MATERIAL.put(Items.COPPER_INGOT, TrimMaterial.Copper);
        INGREDIENT_TO_MATERIAL.put(Items.QUARTZ, TrimMaterial.Quartz);
        INGREDIENT_TO_MATERIAL.put(Items.EMERALD, TrimMaterial.Emerald);
        INGREDIENT_TO_MATERIAL.put(Items.DIAMOND, TrimMaterial.Diamond);
        INGREDIENT_TO_MATERIAL.put(Items.AMETHYST_SHARD, TrimMaterial.Amethyst);
        INGREDIENT_TO_MATERIAL.put(Items.REDSTONE, TrimMaterial.Redstone);
        INGREDIENT_TO_MATERIAL.put(Items.NETHERITE_INGOT, TrimMaterial.Netherite);
    }

    // In 1.21.4 there is no ArmorItem class; armor is identified by the EQUIPPABLE component's slot.
    private boolean isArmor(ItemStack stack) {
        var eq = stack.get(DataComponentTypes.EQUIPPABLE);
        if (eq == null) return false;
        EquipmentSlot slot = eq.slot();
        return slot == EquipmentSlot.HEAD || slot == EquipmentSlot.CHEST
            || slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET;
    }

    private ArmorMaterial getArmorMaterial(ItemStack armor) {
        if (!isArmor(armor)) return net.minecraft.item.equipment.ArmorMaterials.ARMADILLO_SCUTE;

        switch (getItemSlotId(armor)) {
            case 0 -> {
                if (armor.isOf(Items.LEATHER_BOOTS)) return net.minecraft.item.equipment.ArmorMaterials.LEATHER;
                if (armor.isOf(Items.IRON_BOOTS)) return net.minecraft.item.equipment.ArmorMaterials.IRON;
                if (armor.isOf(Items.CHAINMAIL_BOOTS)) return net.minecraft.item.equipment.ArmorMaterials.CHAIN;
                if (armor.isOf(Items.GOLDEN_BOOTS)) return net.minecraft.item.equipment.ArmorMaterials.GOLD;
                if (armor.isOf(Items.DIAMOND_BOOTS)) return net.minecraft.item.equipment.ArmorMaterials.DIAMOND;
                if (armor.isOf(Items.NETHERITE_BOOTS)) return net.minecraft.item.equipment.ArmorMaterials.NETHERITE;
                else return net.minecraft.item.equipment.ArmorMaterials.ARMADILLO_SCUTE;
            }
            case 1 -> {
                if (armor.isOf(Items.LEATHER_LEGGINGS)) return net.minecraft.item.equipment.ArmorMaterials.LEATHER;
                if (armor.isOf(Items.IRON_LEGGINGS)) return net.minecraft.item.equipment.ArmorMaterials.IRON;
                if (armor.isOf(Items.CHAINMAIL_LEGGINGS)) return net.minecraft.item.equipment.ArmorMaterials.CHAIN;
                if (armor.isOf(Items.GOLDEN_LEGGINGS)) return net.minecraft.item.equipment.ArmorMaterials.GOLD;
                if (armor.isOf(Items.DIAMOND_LEGGINGS)) return net.minecraft.item.equipment.ArmorMaterials.DIAMOND;
                if (armor.isOf(Items.NETHERITE_LEGGINGS)) return net.minecraft.item.equipment.ArmorMaterials.NETHERITE;
                else return net.minecraft.item.equipment.ArmorMaterials.ARMADILLO_SCUTE;
            }
            case 2 -> {
                if (armor.isOf(Items.LEATHER_CHESTPLATE)) return net.minecraft.item.equipment.ArmorMaterials.LEATHER;
                if (armor.isOf(Items.IRON_CHESTPLATE)) return net.minecraft.item.equipment.ArmorMaterials.IRON;
                if (armor.isOf(Items.CHAINMAIL_CHESTPLATE)) return net.minecraft.item.equipment.ArmorMaterials.CHAIN;
                if (armor.isOf(Items.GOLDEN_CHESTPLATE)) return net.minecraft.item.equipment.ArmorMaterials.GOLD;
                if (armor.isOf(Items.DIAMOND_CHESTPLATE)) return net.minecraft.item.equipment.ArmorMaterials.DIAMOND;
                if (armor.isOf(Items.NETHERITE_CHESTPLATE)) return net.minecraft.item.equipment.ArmorMaterials.NETHERITE;
                else return net.minecraft.item.equipment.ArmorMaterials.ARMADILLO_SCUTE;
            }
            case 3 -> {
                if (armor.isOf(Items.LEATHER_HELMET)) return net.minecraft.item.equipment.ArmorMaterials.LEATHER;
                if (armor.isOf(Items.IRON_HELMET)) return net.minecraft.item.equipment.ArmorMaterials.IRON;
                if (armor.isOf(Items.CHAINMAIL_HELMET)) return net.minecraft.item.equipment.ArmorMaterials.CHAIN;
                if (armor.isOf(Items.GOLDEN_HELMET)) return net.minecraft.item.equipment.ArmorMaterials.GOLD;
                if (armor.isOf(Items.DIAMOND_HELMET)) return net.minecraft.item.equipment.ArmorMaterials.DIAMOND;
                if (armor.isOf(Items.NETHERITE_HELMET)) return net.minecraft.item.equipment.ArmorMaterials.NETHERITE;
                if (armor.isOf(Items.TURTLE_HELMET)) return net.minecraft.item.equipment.ArmorMaterials.TURTLE_SCUTE;
                else return net.minecraft.item.equipment.ArmorMaterials.ARMADILLO_SCUTE;
            }
            default -> {
                return net.minecraft.item.equipment.ArmorMaterials.ARMADILLO_SCUTE;
            }
        }
    }

    private EquipmentType getEquipmentType(ItemStack stack) {
        return switch (getItemSlotId(stack)) {
            case 0 -> EquipmentType.BOOTS;
            case 1 -> EquipmentType.LEGGINGS;
            case 2 -> EquipmentType.CHESTPLATE;
            case 3 -> EquipmentType.HELMET;
            default -> EquipmentType.BODY;
        };
    }

    private int getItemSlotId(ItemStack itemStack) {
        return itemStack.get(DataComponentTypes.EQUIPPABLE).slot().getEntitySlotId();
    }

    private boolean isValidEquipmentForUpgrading(ItemStack stack) {
        return stack.isOf(Items.DIAMOND_HOE) || stack.isOf(Items.DIAMOND_PICKAXE) || stack.isOf(Items.DIAMOND_AXE)
            || stack.isOf(Items.DIAMOND_SHOVEL) || stack.isOf(Items.DIAMOND_SWORD)  || stack.isOf(Items.DIAMOND_HELMET)
            || stack.isOf(Items.DIAMOND_CHESTPLATE) || stack.isOf(Items.DIAMOND_LEGGINGS) || stack.isOf(Items.DIAMOND_BOOTS);
    }

    private boolean isValidEquipmentForTrimming(ItemStack stack) {
        if (isArmor(stack)) {
            boolean correctMaterial = false;
            EquipmentType equipmentType = getEquipmentType(stack);
            ArmorMaterial armorMaterial = getArmorMaterial(stack);
            if (exhaustedArmorTypes.contains(equipmentType)) return false;
            if (currentlyLookingFor != null && !equipmentType.equals(currentlyLookingFor)) return false;

            correctMaterial = effectiveArmorMaterial(equipmentType).materialEquals(armorMaterial);

            if (!correctMaterial) return false;
            if (stack.contains(DataComponentTypes.TRIM)) {
                if (!overwriteTrims.get()) return false;
                String pattern = stack.get(DataComponentTypes.TRIM).pattern().getIdAsString();
                String material = stack.get(DataComponentTypes.TRIM).material().getIdAsString();

                if (!effectiveTrim(equipmentType).label.equals(pattern) || !effectiveTrimMaterial(equipmentType).label.equals(material)) {
                    if (hasRequiredMaterialsForTrimming(equipmentType)) return true;
                }
            } else return true;
        }
        return false;
    }

    private boolean hasRequiredMaterialsForTrimming(EquipmentType type) {
        boolean hasTemplate = false;
        boolean hasMaterial = false;
        switch (type) {
            case BOOTS -> {
                switch (effectiveTrim(EquipmentType.BOOTS)) {
                    case Eye -> hasTemplate = hasItem(Items.EYE_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Rib -> hasTemplate = hasItem(Items.RIB_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Vex -> hasTemplate = hasItem(Items.VEX_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Dune -> hasTemplate = hasItem(Items.DUNE_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Host -> hasTemplate = hasItem(Items.HOST_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Tide -> hasTemplate = hasItem(Items.TIDE_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Ward -> hasTemplate = hasItem(Items.WARD_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Wild -> hasTemplate = hasItem(Items.WILD_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Bolt -> hasMaterial = hasItem(Items.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Flow -> hasMaterial = hasItem(Items.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Coast -> hasTemplate = hasItem(Items.COAST_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Snout -> hasTemplate = hasItem(Items.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Spire -> hasTemplate = hasItem(Items.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Raiser -> hasTemplate = hasItem(Items.RAISER_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Sentry -> hasTemplate = hasItem(Items.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Shaper -> hasTemplate = hasItem(Items.SHAPER_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Silence -> hasTemplate = hasItem(Items.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Wayfinder -> hasTemplate = hasItem(Items.WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE);
                }
                switch (effectiveTrimMaterial(EquipmentType.BOOTS)) {
                    case Iron -> hasMaterial = hasItem(Items.IRON_INGOT);
                    case Gold -> hasMaterial = hasItem(Items.GOLD_INGOT);
                    case Lapis -> hasMaterial = hasItem(Items.LAPIS_LAZULI);
                    case Quartz -> hasMaterial = hasItem(Items.QUARTZ);
                    case Resin -> hasMaterial = hasItem(Items.RESIN_BRICK);
                    case Copper -> hasMaterial = hasItem(Items.COPPER_INGOT);
                    case Emerald -> hasMaterial = hasItem(Items.EMERALD);
                    case Diamond -> hasMaterial = hasItem(Items.DIAMOND);
                    case Amethyst -> hasMaterial = hasItem(Items.AMETHYST_SHARD);
                    case Redstone -> hasMaterial = hasItem(Items.REDSTONE);
                    case Netherite -> hasMaterial = hasItem(Items.NETHERITE_INGOT);
                }
                if (hasTemplate && hasMaterial) return true;
            }
            case HELMET -> {
                switch (effectiveTrim(EquipmentType.HELMET)) {
                    case Eye -> hasTemplate = hasItem(Items.EYE_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Rib -> hasTemplate = hasItem(Items.RIB_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Vex -> hasTemplate = hasItem(Items.VEX_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Dune -> hasTemplate = hasItem(Items.DUNE_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Host -> hasTemplate = hasItem(Items.HOST_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Tide -> hasTemplate = hasItem(Items.TIDE_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Ward -> hasTemplate = hasItem(Items.WARD_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Wild -> hasTemplate = hasItem(Items.WILD_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Bolt -> hasMaterial = hasItem(Items.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Flow -> hasMaterial = hasItem(Items.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Coast -> hasTemplate = hasItem(Items.COAST_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Snout -> hasTemplate = hasItem(Items.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Spire -> hasTemplate = hasItem(Items.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Raiser -> hasTemplate = hasItem(Items.RAISER_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Sentry -> hasTemplate = hasItem(Items.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Shaper -> hasTemplate = hasItem(Items.SHAPER_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Silence -> hasTemplate = hasItem(Items.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Wayfinder -> hasTemplate = hasItem(Items.WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE);
                }
                switch (effectiveTrimMaterial(EquipmentType.HELMET)) {
                    case Iron -> hasMaterial = hasItem(Items.IRON_INGOT);
                    case Gold -> hasMaterial = hasItem(Items.GOLD_INGOT);
                    case Lapis -> hasMaterial = hasItem(Items.LAPIS_LAZULI);
                    case Quartz -> hasMaterial = hasItem(Items.QUARTZ);
                    case Resin -> hasMaterial = hasItem(Items.RESIN_BRICK);
                    case Copper -> hasMaterial = hasItem(Items.COPPER_INGOT);
                    case Emerald -> hasMaterial = hasItem(Items.EMERALD);
                    case Diamond -> hasMaterial = hasItem(Items.DIAMOND);
                    case Amethyst -> hasMaterial = hasItem(Items.AMETHYST_SHARD);
                    case Redstone -> hasMaterial = hasItem(Items.REDSTONE);
                    case Netherite -> hasMaterial = hasItem(Items.NETHERITE_INGOT);
                }
                if (hasTemplate && hasMaterial) return true;
            }
            case LEGGINGS -> {
                switch (effectiveTrim(EquipmentType.LEGGINGS)) {
                    case Eye -> hasTemplate = hasItem(Items.EYE_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Rib -> hasTemplate = hasItem(Items.RIB_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Vex -> hasTemplate = hasItem(Items.VEX_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Dune -> hasTemplate = hasItem(Items.DUNE_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Host -> hasTemplate = hasItem(Items.HOST_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Tide -> hasTemplate = hasItem(Items.TIDE_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Ward -> hasTemplate = hasItem(Items.WARD_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Wild -> hasTemplate = hasItem(Items.WILD_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Bolt -> hasMaterial = hasItem(Items.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Flow -> hasMaterial = hasItem(Items.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Coast -> hasTemplate = hasItem(Items.COAST_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Snout -> hasTemplate = hasItem(Items.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Spire -> hasTemplate = hasItem(Items.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Raiser -> hasTemplate = hasItem(Items.RAISER_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Sentry -> hasTemplate = hasItem(Items.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Shaper -> hasTemplate = hasItem(Items.SHAPER_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Silence -> hasTemplate = hasItem(Items.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Wayfinder -> hasTemplate = hasItem(Items.WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE);
                }
                switch (effectiveTrimMaterial(EquipmentType.LEGGINGS)) {
                    case Iron -> hasMaterial = hasItem(Items.IRON_INGOT);
                    case Gold -> hasMaterial = hasItem(Items.GOLD_INGOT);
                    case Lapis -> hasMaterial = hasItem(Items.LAPIS_LAZULI);
                    case Quartz -> hasMaterial = hasItem(Items.QUARTZ);
                    case Resin -> hasMaterial = hasItem(Items.RESIN_BRICK);
                    case Copper -> hasMaterial = hasItem(Items.COPPER_INGOT);
                    case Emerald -> hasMaterial = hasItem(Items.EMERALD);
                    case Diamond -> hasMaterial = hasItem(Items.DIAMOND);
                    case Amethyst -> hasMaterial = hasItem(Items.AMETHYST_SHARD);
                    case Redstone -> hasMaterial = hasItem(Items.REDSTONE);
                    case Netherite -> hasMaterial = hasItem(Items.NETHERITE_INGOT);
                }
                if (hasTemplate && hasMaterial) return true;
            }
            case CHESTPLATE -> {
                switch (effectiveTrim(EquipmentType.CHESTPLATE)) {
                    case Eye -> hasTemplate = hasItem(Items.EYE_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Rib -> hasTemplate = hasItem(Items.RIB_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Vex -> hasTemplate = hasItem(Items.VEX_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Dune -> hasTemplate = hasItem(Items.DUNE_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Host -> hasTemplate = hasItem(Items.HOST_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Tide -> hasTemplate = hasItem(Items.TIDE_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Ward -> hasTemplate = hasItem(Items.WARD_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Wild -> hasTemplate = hasItem(Items.WILD_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Bolt -> hasMaterial = hasItem(Items.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Flow -> hasMaterial = hasItem(Items.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Coast -> hasTemplate = hasItem(Items.COAST_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Snout -> hasTemplate = hasItem(Items.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Spire -> hasTemplate = hasItem(Items.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Raiser -> hasTemplate = hasItem(Items.RAISER_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Sentry -> hasTemplate = hasItem(Items.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Shaper -> hasTemplate = hasItem(Items.SHAPER_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Silence -> hasTemplate = hasItem(Items.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE);
                    case Wayfinder -> hasTemplate = hasItem(Items.WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE);
                }
                switch (effectiveTrimMaterial(EquipmentType.CHESTPLATE)) {
                    case Iron -> hasMaterial = hasItem(Items.IRON_INGOT);
                    case Gold -> hasMaterial = hasItem(Items.GOLD_INGOT);
                    case Lapis -> hasMaterial = hasItem(Items.LAPIS_LAZULI);
                    case Quartz -> hasMaterial = hasItem(Items.QUARTZ);
                    case Resin -> hasMaterial = hasItem(Items.RESIN_BRICK);
                    case Copper -> hasMaterial = hasItem(Items.COPPER_INGOT);
                    case Emerald -> hasMaterial = hasItem(Items.EMERALD);
                    case Diamond -> hasMaterial = hasItem(Items.DIAMOND);
                    case Amethyst -> hasMaterial = hasItem(Items.AMETHYST_SHARD);
                    case Redstone -> hasMaterial = hasItem(Items.REDSTONE);
                    case Netherite -> hasMaterial = hasItem(Items.NETHERITE_INGOT);
                }
                if (hasTemplate && hasMaterial) return true;
            }
        }

        return false;
    }

    private boolean hasItem(Item needed) {
        if (mc.player == null) return false;
        if (!(mc.player.currentScreenHandler instanceof SmithingScreenHandler ss)) return false;

        for (int n = 0; n < mc.player.currentScreenHandler.slots.size(); n++) {
            ItemStack stack = ss.getSlot(n).getStack();
            if (stack.getItem() == needed) return true;
        }
        return false;
    }

    // --- debug helpers ---
    private String describe(ItemStack stack) {
        if (stack.isEmpty()) return "empty";
        return stack.getCount() + "x " + stack.getItem();
    }

    private int countItem(SmithingScreenHandler ss, Item item) {
        int total = 0;
        for (int n = 4; n < ss.slots.size(); n++) {
            ItemStack s = ss.getSlot(n).getStack();
            if (s.getItem() == item) total += s.getCount();
        }
        return total;
    }

    // Counts inventory pieces eligible for the current operating mode (read-only estimate).
    private int countMatching(SmithingScreenHandler ss, boolean upgrade) {
        int total = 0;
        for (int n = 4; n < ss.slots.size(); n++) {
            ItemStack s = ss.getSlot(n).getStack();
            if (upgrade ? isValidEquipmentForUpgrading(s) : isValidEquipmentForTrimming(s)) total++;
        }
        return total;
    }

    // Scans inventory once per activation and picks the most common armor material, trim pattern,
    // and trim material so Trim mode can run without manually configuring all 12 per-piece settings.
    private void runAutoDetect(SmithingScreenHandler ss) {
        if (autoDetectRan) return;
        autoDetectRan = true;

        if (debug.get()) info("Auto-detect: scanning inventory...");

        Map<ArmorMaterials, Integer> materialTally = new HashMap<>();
        Map<ArmorTrims, Integer> trimTally = new HashMap<>();
        Map<TrimMaterial, Integer> ingredientTally = new HashMap<>();

        for (int n = 4; n < ss.slots.size(); n++) {
            ItemStack stack = ss.getSlot(n).getStack();
            if (stack.isEmpty()) continue;

            if (isArmor(stack)) {
                ArmorMaterial mat = getArmorMaterial(stack);
                for (ArmorMaterials am : ArmorMaterials.values()) {
                    if (am.materialEquals(mat)) {
                        materialTally.merge(am, stack.getCount(), Integer::sum);
                        break;
                    }
                }
            }

            ArmorTrims trim = TEMPLATE_TO_TRIM.get(stack.getItem());
            if (trim != null) trimTally.merge(trim, stack.getCount(), Integer::sum);

            TrimMaterial ingredient = INGREDIENT_TO_MATERIAL.get(stack.getItem());
            if (ingredient != null) ingredientTally.merge(ingredient, stack.getCount(), Integer::sum);
        }

        if (debug.get()) {
            info("Auto-detect: material tally = " + materialTally);
            info("Auto-detect: trim tally = " + trimTally);
            info("Auto-detect: trim-material tally = " + ingredientTally);
        }

        detectedArmorMaterial = materialTally.entrySet().stream()
            .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        detectedTrimPattern = trimTally.entrySet().stream()
            .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        detectedTrimMaterial = ingredientTally.entrySet().stream()
            .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);

        info("Auto-detected: material=" + describeDetection(materialTally, detectedArmorMaterial)
            + " trim=" + describeDetection(trimTally, detectedTrimPattern)
            + " trim-material=" + describeDetection(ingredientTally, detectedTrimMaterial));
    }

    // Reports an ambiguous tie instead of silently picking whichever HashMap iteration happened to land on.
    private <T> String describeDetection(Map<T, Integer> tally, @Nullable T picked) {
        if (picked == null) return "none found";
        int max = tally.get(picked);
        long tiedCount = tally.values().stream().filter(v -> v == max).count();
        if (tiedCount > 1) return "couldn't determine (tied, defaulted to " + picked + ")";
        return picked.toString();
    }

    private ArmorMaterials effectiveArmorMaterial(EquipmentType type) {
        if (autoDetect.get() && detectedArmorMaterial != null) return detectedArmorMaterial;
        return switch (type) {
            case HELMET -> helmetType.get();
            case CHESTPLATE -> chestplateType.get();
            case LEGGINGS -> leggingsType.get();
            default -> bootsType.get();
        };
    }

    private ArmorTrims effectiveTrim(EquipmentType type) {
        if (autoDetect.get() && detectedTrimPattern != null) return detectedTrimPattern;
        return switch (type) {
            case HELMET -> helmetTrim.get();
            case CHESTPLATE -> chestplateTrim.get();
            case LEGGINGS -> leggingsTrim.get();
            default -> bootsTrim.get();
        };
    }

    private TrimMaterial effectiveTrimMaterial(EquipmentType type) {
        if (autoDetect.get() && detectedTrimMaterial != null) return detectedTrimMaterial;
        return switch (type) {
            case HELMET -> helmetTrimMaterial.get();
            case CHESTPLATE -> chestplateTrimMaterial.get();
            case LEGGINGS -> leggingsTrimMaterial.get();
            default -> bootsTrimMaterial.get();
        };
    }

    @Override
    public void onDeactivate() {
        timer = 0;
        trimStack = null;
        notified = false;
        foundEquip = false;
        foundIngots = false;
        materialStack = null;
        equipmentStack = null;
        foundTemplates = false;
        processedSlots.clear();
        projectedEmpty.clear();
        currentlyLookingFor = null;
        resettingTemplates = false;
        resettingMaterials = false;
        exhaustedArmorTypes.clear();
        autoDetectRan = false;
        detectedArmorMaterial = null;
        detectedTrimPattern = null;
        detectedTrimMaterial = null;
    }

    @EventHandler
    private void onScreenOpened(OpenScreenEvent event) {
        if (event.screen instanceof SmithingScreen) {
            notified = false;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (mc.getNetworkHandler() == null) return;
        if (mc.currentScreen == null) onDeactivate();
        if (!(mc.player.currentScreenHandler instanceof SmithingScreenHandler ss)) return;

        switch (moduleMode.get()) {
            case Packet -> {
                if (notified) return;

                if (debug.get()) {
                    info("== Packet run == mode=" + operatingMode.get()
                        + " syncId=" + ss.syncId + " rev=" + ss.getRevision());
                    info("Slots: tmpl=" + describe(ss.getSlot(SmithingScreenHandler.TEMPLATE_ID).getStack())
                        + " mat=" + describe(ss.getSlot(SmithingScreenHandler.MATERIAL_ID).getStack())
                        + " equip=" + describe(ss.getSlot(SmithingScreenHandler.EQUIPMENT_ID).getStack())
                        + " out=" + describe(ss.getSlot(SmithingScreenHandler.OUTPUT_ID).getStack()));
                    if (operatingMode.get() == SmithingMode.Upgrade) {
                        info("Inventory: upgradable=" + countMatching(ss, true)
                            + " netheriteIngots=" + countItem(ss, Items.NETHERITE_INGOT)
                            + " netheriteTemplates=" + countItem(ss, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
                    } else {
                        info("Inventory: trimmable=" + countMatching(ss, false));
                    }
                }

                ArrayDeque<ClickSlotC2SPacket> packetQueue = new ArrayDeque<>();

                boolean exhausted = false;
                while (!exhausted) {
                    ClickSlotC2SPacket packet = generateSmithingPacket(ss);

                    if (packet == null) {
                        exhausted = true;
                    } else if (packetQueue.size() >= packetLimit.get()) {
                        exhausted = true;
                        packetQueue.addLast(packet);
                        info("Packet limit was hit..! You may need to run the module again...");
                    } else packetQueue.addLast(packet);
                }

                if (debug.get()) {
                    info("Generated " + packetQueue.size() + " packet(s); sending...");
                    int i = 0;
                    for (ClickSlotC2SPacket p : packetQueue) {
                        info("  packet[" + (i++) + "] slot=" + p.slot()
                            + " action=" + p.actionType() + " modified=" + p.modifiedStacks().size());
                    }
                    if (packetQueue.isEmpty()) {
                        info("No packets generated — nothing matched. Check the slot/inventory lines above.");
                    }
                }
                while (!packetQueue.isEmpty()) {
                    mc.getNetworkHandler().sendPacket(packetQueue.removeFirst());
                }

                finished();
            }
            case Interact -> {
                if (timer >= tickRate.get()) {
                    timer = 0;
                } else {
                    ++timer;
                    return;
                }

                if (resettingTemplates) {
                    InvUtils.shiftClick().slotId(SmithingScreenHandler.TEMPLATE_ID);
                    timer = tickRate.get() - 1;
                    resettingTemplates = false;
                    return;
                } else if (resettingMaterials) {
                    InvUtils.shiftClick().slotId(SmithingScreenHandler.MATERIAL_ID);
                    timer = tickRate.get() - 1;
                    resettingMaterials = false;
                    return;
                }
                switch (operatingMode.get()) {
                    case Trim -> {
                        if (autoDetect.get()) runAutoDetect(ss);
                        ItemStack output = ss.getSlot(SmithingScreenHandler.OUTPUT_ID).getStack();

                        if (!output.isEmpty()) {
                            if (!isArmor(output)) return;
                            EquipmentType armorType = getEquipmentType(output);
                            if (output.contains(DataComponentTypes.TRIM)) {
                                ArmorTrim trimData = output.get(DataComponentTypes.TRIM);
                                String pattern = trimData.pattern().getIdAsString();
                                String material = trimData.material().getIdAsString();
                                switch (armorType) {
                                    case BOOTS -> {
                                        if (!effectiveTrim(EquipmentType.BOOTS).label.equals(pattern)) {
                                            foundTemplates = false;
                                            InvUtils.shiftClick().slotId(SmithingScreenHandler.TEMPLATE_ID);
                                        } else if (!effectiveTrimMaterial(EquipmentType.BOOTS).label.equals(material)) {
                                            foundIngots = false;
                                            InvUtils.shiftClick().slotId(SmithingScreenHandler.MATERIAL_ID);
                                        } else {
                                            InvUtils.shiftClick().slotId(SmithingScreenHandler.OUTPUT_ID);

                                            foundEquip = false;
                                            foundIngots = false;
                                            foundTemplates = false;
                                            if (ss.getSlot(SmithingScreenHandler.TEMPLATE_ID).getStack().getCount() >= 1) resettingTemplates = true;
                                            if (ss.getSlot(SmithingScreenHandler.MATERIAL_ID).getStack().getCount() >= 1) resettingMaterials = true;
                                        }
                                    }
                                    case HELMET -> {
                                        if (!effectiveTrim(EquipmentType.HELMET).label.equals(pattern)) {
                                            foundTemplates = false;
                                            InvUtils.shiftClick().slotId(SmithingScreenHandler.TEMPLATE_ID);
                                        } else if (!effectiveTrimMaterial(EquipmentType.HELMET).label.equals(material)) {
                                            foundIngots = false;
                                            InvUtils.shiftClick().slotId(SmithingScreenHandler.MATERIAL_ID);
                                        } else {
                                            InvUtils.shiftClick().slotId(SmithingScreenHandler.OUTPUT_ID);

                                            foundEquip = false;
                                            foundIngots = false;
                                            foundTemplates = false;
                                            if (ss.getSlot(SmithingScreenHandler.TEMPLATE_ID).getStack().getCount() >= 1) resettingTemplates = true;
                                            if (ss.getSlot(SmithingScreenHandler.MATERIAL_ID).getStack().getCount() >= 1) resettingMaterials = true;
                                        }
                                    }
                                    case LEGGINGS -> {
                                        if (!effectiveTrim(EquipmentType.LEGGINGS).label.equals(pattern)) {
                                            foundTemplates = false;
                                            InvUtils.shiftClick().slotId(SmithingScreenHandler.TEMPLATE_ID);
                                        } else if (!effectiveTrimMaterial(EquipmentType.LEGGINGS).label.equals(material)) {
                                            foundIngots = false;
                                            InvUtils.shiftClick().slotId(SmithingScreenHandler.MATERIAL_ID);
                                        } else {
                                            InvUtils.shiftClick().slotId(SmithingScreenHandler.OUTPUT_ID);

                                            foundEquip = false;
                                            foundIngots = false;
                                            foundTemplates = false;
                                            if (ss.getSlot(SmithingScreenHandler.TEMPLATE_ID).getStack().getCount() >= 1) resettingTemplates = true;
                                            if (ss.getSlot(SmithingScreenHandler.MATERIAL_ID).getStack().getCount() >= 1) resettingMaterials = true;
                                        }
                                    }
                                    case CHESTPLATE -> {
                                        if (!effectiveTrim(EquipmentType.CHESTPLATE).label.equals(pattern)) {
                                            foundTemplates = false;
                                            InvUtils.shiftClick().slotId(SmithingScreenHandler.TEMPLATE_ID);
                                        } else if (!effectiveTrimMaterial(EquipmentType.CHESTPLATE).label.equals(material)) {
                                            foundIngots = false;
                                            InvUtils.shiftClick().slotId(SmithingScreenHandler.MATERIAL_ID);
                                        } else {
                                            InvUtils.shiftClick().slotId(SmithingScreenHandler.OUTPUT_ID);

                                            foundEquip = false;
                                            foundIngots = false;
                                            foundTemplates = false;
                                            if (ss.getSlot(SmithingScreenHandler.TEMPLATE_ID).getStack().getCount() >= 1) resettingTemplates = true;
                                            if (ss.getSlot(SmithingScreenHandler.MATERIAL_ID).getStack().getCount() >= 1) resettingMaterials = true;
                                        }
                                    }
                                }
                            } else {
                                foundEquip = false;
                                InvUtils.shiftClick().slotId(SmithingScreenHandler.EQUIPMENT_ID);

                                foundIngots = false;
                                foundTemplates = false;
                                resettingTemplates = true;
                                resettingMaterials = false;
                            }
                        } else if (!foundEquip) {
                            for (int n = 4; n < mc.player.currentScreenHandler.slots.size(); n++) {
                                ItemStack stack = ss.getSlot(n).getStack();
                                if (isValidEquipmentForTrimming(stack)) {
                                    foundEquip = true;
                                    InvUtils.shiftClick().slotId(n);
                                    break;
                                }
                            }
                            if (!foundEquip && !notified) {
                                info("No armor left to trim.");
                                finished();
                            }
                        } else if (!foundIngots) {
                            ItemStack armorToTrim = ss.getSlot(SmithingScreenHandler.EQUIPMENT_ID).getStack();
                            if (!isArmor(armorToTrim)) {
                                foundEquip = false;
                                resettingTemplates = true;
                                resettingMaterials = true;
                                InvUtils.shiftClick().slotId(SmithingScreenHandler.EQUIPMENT_ID);
                                error("Item in equipment slot was not armor..!");
                                return;
                            }
                            EquipmentType armorType = getEquipmentType(armorToTrim);
                            Item neededMaterial = getNeededMaterialItem(armorToTrim);

                            if (neededMaterial == null) {
                                error("neededMaterial was somehow null!");
                                return;
                            }
                            for (int n = 4; n < mc.player.currentScreenHandler.slots.size(); n++) {
                                ItemStack stack = ss.getSlot(n).getStack();
                                if (stack.isOf(neededMaterial)) {
                                    foundIngots = true;
                                    InvUtils.shiftClick().slotId(n);
                                    break;
                                }
                            }
                            if (!foundIngots && !notified) {
                                if (!exhaustedArmorTypes.contains(armorType)) {
                                    exhaustedArmorTypes.add(armorType);
                                    return;
                                }
                                info("No valid trim materials left to use..!");
                                finished();
                            }
                        } else if (!foundTemplates) {
                            ItemStack armorToTrim = ss.getSlot(SmithingScreenHandler.EQUIPMENT_ID).getStack();
                            if (!isArmor(armorToTrim)) {
                                foundEquip = false;
                                resettingTemplates = true;
                                resettingMaterials = true;
                                InvUtils.shiftClick().slotId(SmithingScreenHandler.EQUIPMENT_ID);
                                error("Item in equipment slot was not armor!");
                                return;
                            }

                            EquipmentType armorType = getEquipmentType(armorToTrim);
                            Item neededPattern = getNeededPatternItem(armorToTrim);
                            if (neededPattern == null) {
                                error("neededPattern was somehow null!");
                                return;
                            }
                            for (int n = 4; n < mc.player.currentScreenHandler.slots.size(); n++) {
                                ItemStack stack = ss.getSlot(n).getStack();
                                if (stack.getItem() == neededPattern) {
                                    foundTemplates = true;
                                    InvUtils.shiftClick().slotId(n);
                                    break;
                                }
                            }
                            if (!foundTemplates && !notified) {
                                if (!exhaustedArmorTypes.contains(armorType)) {
                                    exhaustedArmorTypes.add(armorType);
                                    return;
                                }
                                info("No valid trim templates left to use..!");
                                finished();
                            }
                        } else {
                            timer = tickRate.get() - 1;
                        }
                    }
                    case Upgrade -> {
                        ItemStack output = ss.getSlot(SmithingScreenHandler.OUTPUT_ID).getStack();
                        if (!output.isEmpty()) {
                            InvUtils.shiftClick().slotId(SmithingScreenHandler.OUTPUT_ID);

                            foundEquip = false;
                            int ingotsRemaining = ss.getSlot(SmithingScreenHandler.MATERIAL_ID).getStack().getCount();
                            int templatesRemaining = ss.getSlot(SmithingScreenHandler.TEMPLATE_ID).getStack().getCount();

                            if (ingotsRemaining == 0) foundIngots = false;
                            if (templatesRemaining == 0) foundTemplates = false;
                        } else if (!foundEquip) {
                            for (int n = 4; n < mc.player.currentScreenHandler.slots.size(); n++) {
                                ItemStack stack = ss.getSlot(n).getStack();
                                if (isValidEquipmentForUpgrading(stack)) {
                                    foundEquip = true;
                                    InvUtils.shiftClick().slotId(n);
                                    break;
                                }
                            }
                            if (!foundEquip && !notified) {
                                info("No gear left to upgrade..!");
                                finished();
                            }
                        }else if (!foundIngots) {
                            for (int n = 4; n < mc.player.currentScreenHandler.slots.size(); n++) {
                                ItemStack stack = ss.getSlot(n).getStack();
                                if (stack.getItem() == Items.NETHERITE_INGOT) {
                                    foundIngots = true;
                                    InvUtils.shiftClick().slotId(n);
                                    break;
                                }
                            }
                            if (!foundIngots && !notified) {
                                info("No netherite ingots left to use..!");
                                finished();
                            }
                        } else if (!foundTemplates) {
                            for (int n = 4; n < mc.player.currentScreenHandler.slots.size(); n++) {
                                ItemStack stack = ss.getSlot(n).getStack();
                                if (stack.getItem() == Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE) {
                                    foundTemplates = true;
                                    InvUtils.shiftClick().slotId(n);
                                    break;
                                }
                            }
                            if (!foundTemplates && !notified) {
                                info("No netherite smithing templates left to use..!");
                                finished();
                            }
                        } else {
                            timer = tickRate.get() - 1;
                        }
                    }
                }
            }
        }
    }

    private void finished() {
        if (mc.player == null) {
            notified = true;
            return;
        }
        if (!notified) {
            info("Finished processing items..!");
            if (pingOnDone.get()) {
                mc.player.playSound(
                    SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                    pingVolume.get().floatValue(),
                    ThreadLocalRandom.current().nextFloat(0.69f, 1.337f)
                );
            }
        }
        notified = true;
        if (closeOnDone.get()) mc.player.closeHandledScreen();
        if (disableOnDone.get()) toggle();
    }

    // In 1.21.11 ClickSlotC2SPacket sends hashed stacks; convert the predicted ItemStack map into ItemStackHash form.
    private ClickSlotC2SPacket buildSmithingPacket(SmithingScreenHandler handler, int slot, Int2ObjectMap<ItemStack> stacks) {
        ComponentChangesHash.ComponentHasher hasher = mc.getNetworkHandler().getComponentHasher();
        Int2ObjectMap<ItemStackHash> hashed = new Int2ObjectOpenHashMap<>();
        for (Int2ObjectMap.Entry<ItemStack> entry : stacks.int2ObjectEntrySet()) {
            hashed.put(entry.getIntKey(), ItemStackHash.fromItemStack(entry.getValue(), hasher));
        }
        return new ClickSlotC2SPacket(
            handler.syncId, handler.getRevision(), (short) slot, (byte) 0,
            SlotActionType.QUICK_MOVE, hashed, ItemStackHash.EMPTY
        );
    }

    @SuppressWarnings("deprecation")
    private @Nullable ClickSlotC2SPacket generateSmithingPacket(SmithingScreenHandler handler) {
        if (mc.player == null) return null;
        if (operatingMode.get() == SmithingMode.Trim && autoDetect.get()) runAutoDetect(handler);
        Int2ObjectMap<ItemStack> changedSlots = new Int2ObjectOpenHashMap<>();
        if (debug.get()) {
            info("gen: eq=" + (equipmentStack == null ? "-" : describe(equipmentStack))
                + " mat=" + (materialStack == null ? "-" : describe(materialStack))
                + " tmpl=" + (trimStack == null ? "-" : describe(trimStack))
                + " lookingFor=" + (currentlyLookingFor == null ? "-" : currentlyLookingFor.name())
                + " processed=" + processedSlots.size());
        }
        if (trimStack != null && materialStack != null && equipmentStack != null) {
            // check if correct and take output

            ItemStack armorToTrim = equipmentStack;
            if (operatingMode.get().equals(SmithingMode.Trim) && !isArmor(armorToTrim)) {
                error("Item in equipment slot was not armor..!");
                return null;
            }

            Item neededPattern;
            Item neededMaterial;
            if (operatingMode.get().equals(SmithingMode.Trim)) {
                neededPattern = getNeededPatternItem(armorToTrim);
                neededMaterial = getNeededMaterialItem(armorToTrim);
            } else {
                neededMaterial = Items.NETHERITE_INGOT;
                neededPattern = Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE;
            }

            if (operatingMode.get().equals(SmithingMode.Trim) && !trimStack.isOf(neededPattern)) {
                if (debug.get()) {
                    info("Wrong trim stack for armor of type "
                        + getEquipmentType(armorToTrim).name() + "..!");
                }
                changedSlots.put(SmithingScreenHandler.TEMPLATE_ID, ItemStack.EMPTY);

                int shiftClickTargetSlot = predictEmptySlot(handler);
                if (shiftClickTargetSlot == -1) {
                    info("Failed to predict empty target slot...!");
                    return null;
                }

                changedSlots.put(shiftClickTargetSlot, trimStack.copy());
                trimStack = null;

                if (debug.get()) {
                    info("Moving incorrect template item back to inventory..!");
                }
                return buildSmithingPacket(handler, SmithingScreenHandler.TEMPLATE_ID, changedSlots);
            }
            if (operatingMode.get().equals(SmithingMode.Trim) && !materialStack.isOf(neededMaterial)) {
                if (debug.get()) {
                    info("Wrong material stack for armor of type "
                        + getEquipmentType(armorToTrim).name() + "..!");
                }
                changedSlots.put(SmithingScreenHandler.MATERIAL_ID, ItemStack.EMPTY);

                int shiftClickTargetSlot = predictEmptySlot(handler);
                if (shiftClickTargetSlot == -1) {
                    info("Failed to predict empty target slot....!");
                    return null;
                }

                changedSlots.put(shiftClickTargetSlot, materialStack.copy());
                materialStack = null;

                if (debug.get()) {
                    info("Moving incorrect material item back to inventory..!");
                }
                return buildSmithingPacket(handler, SmithingScreenHandler.MATERIAL_ID, changedSlots);
            }

            // take output
            int trimCount = trimStack.getCount();
            int materialCount = materialStack.getCount();
            changedSlots.put(SmithingScreenHandler.OUTPUT_ID, ItemStack.EMPTY);
            changedSlots.put(SmithingScreenHandler.EQUIPMENT_ID, ItemStack.EMPTY);

            if (trimCount - 1 > 0) {
                ItemStack newTrimStack = trimStack.copyWithCount(trimCount - 1);
                changedSlots.put(SmithingScreenHandler.TEMPLATE_ID, newTrimStack);
                trimStack = newTrimStack;
            } else {
                changedSlots.put(SmithingScreenHandler.TEMPLATE_ID, ItemStack.EMPTY);
                trimStack = null;
            }

            if (materialCount - 1 > 0) {
                ItemStack newMaterialStack = materialStack.copyWithCount(materialCount - 1);
                changedSlots.put(SmithingScreenHandler.MATERIAL_ID, newMaterialStack);
                materialStack = newMaterialStack;
            } else {
                changedSlots.put(SmithingScreenHandler.MATERIAL_ID, ItemStack.EMPTY);
                materialStack = null;
            }

            int shiftClickTargetSlot = predictEmptySlot(handler);
            if (shiftClickTargetSlot == -1) {
                info("Failed to predict empty target slot..!");
                return null;
            }

            if (operatingMode.get().equals(SmithingMode.Trim)) {
                // todo: fabricate trim components for output stack if needed (currently not needed)

                ItemStack output = new ItemStack(
                    armorToTrim.getItem().getRegistryEntry(),
                    armorToTrim.getCount(), armorToTrim.getComponentChanges()
                );

                changedSlots.put(shiftClickTargetSlot, output);
            } else {
                ItemStack output = getUpgradedItem(armorToTrim);
                changedSlots.put(shiftClickTargetSlot, output);
            }

            if (debug.get()) info("Generated output packet..!");
            equipmentStack = null;
            return buildSmithingPacket(handler, 3, changedSlots);
        } else if (equipmentStack == null) {
            // look for valid equipment stack

            if (currentlyLookingFor == null && operatingMode.get().equals(SmithingMode.Trim)) {
                currentlyLookingFor = computeLookingFor();
                if (debug.get() && currentlyLookingFor != null) {
                    info("Currently looking for equipment of type: " + currentlyLookingFor.name());
                }
            }
            for (int n = 4; n < mc.player.currentScreenHandler.slots.size(); n++) {
                if (processedSlots.contains(n)) continue;
                ItemStack stack = handler.getSlot(n).getStack();
                if ((operatingMode.get().equals(SmithingMode.Trim) && isValidEquipmentForTrimming(stack)) || (operatingMode.get().equals(SmithingMode.Upgrade) && isValidEquipmentForUpgrading(stack))) {
                    equipmentStack = stack;
                    processedSlots.add(n);
                    projectedEmpty.add(n);
                    processedSlots.add(SmithingScreenHandler.EQUIPMENT_ID);

                    changedSlots.put(SmithingScreenHandler.EQUIPMENT_ID, stack);
                    changedSlots.put(n, ItemStack.EMPTY);

                    if (trimStack != null && materialStack != null) {
                        if (operatingMode.get().equals(SmithingMode.Trim)) {
                            ItemStack output = new ItemStack(
                                stack.getItem().getRegistryEntry(),
                                stack.getCount(), stack.getComponentChanges()
                            );

                            // todo: fabricate trim components for output stack if needed (currently not needed)
                            changedSlots.put(SmithingScreenHandler.OUTPUT_ID, output);
                        } else {
                            ItemStack output = getUpgradedItem(equipmentStack);
                            changedSlots.put(SmithingScreenHandler.OUTPUT_ID, output);
                        }
                    }

                    if (debug.get() && currentlyLookingFor != null) {
                        info("Found valid armor piece of type: "
                            + currentlyLookingFor.getName() + "..!");
                    }
                    return buildSmithingPacket(handler, n, changedSlots);
                }
            }

            if (operatingMode.get().equals(SmithingMode.Trim)) {
                if (debug.get() && currentlyLookingFor != null) {
                    info("Exhausted all available armor of type: " + currentlyLookingFor.name());
                }
                exhaustedArmorTypes.add(currentlyLookingFor);
                currentlyLookingFor = null;
                if (exhaustedArmorTypes.size() < 4) {
                    if (debug.get()) {
                        info("Recursing to search for the next type..!");
                    }
                    return generateSmithingPacket(handler);
                } else if (debug.get()) {
                    info("Exhausted all armor of all types, no more armor to trim..!");
                }
            }
        } else if (materialStack == null) {
            ItemStack armorToTrim = equipmentStack;
            if (operatingMode.get().equals(SmithingMode.Trim) && !isArmor(armorToTrim)) {
                error("Item in equipment slot was not armor..!");
                return null;
            }
            Item needed;
            if (operatingMode.get().equals(SmithingMode.Trim)) {
                needed = getNeededMaterialItem(armorToTrim);
            } else {
                needed = Items.NETHERITE_INGOT;
            }
            for (int n = 4; n < mc.player.currentScreenHandler.slots.size(); n++) {
                if (processedSlots.contains(n)) continue;
                ItemStack stack = handler.getSlot(n).getStack();
                if (stack.isOf(needed)) {
                    materialStack = stack;
                    processedSlots.add(n);
                    projectedEmpty.add(n);
                    processedSlots.add(SmithingScreenHandler.MATERIAL_ID);

                    changedSlots.put(SmithingScreenHandler.MATERIAL_ID, stack);
                    changedSlots.put(n, ItemStack.EMPTY);

                    if (trimStack != null) {
                        if (operatingMode.get().equals(SmithingMode.Trim)) {
                            // todo: fabricate trim components for output stack if needed (currently not needed)

                            ItemStack output = new ItemStack(
                                stack.getItem().getRegistryEntry(),
                                stack.getCount(), stack.getComponentChanges()
                            );

                            changedSlots.put(SmithingScreenHandler.OUTPUT_ID, output);
                        } else {
                            ItemStack output = getUpgradedItem(equipmentStack);
                            changedSlots.put(SmithingScreenHandler.OUTPUT_ID, output);
                        }
                    }

                    return buildSmithingPacket(handler, n, changedSlots);
                }
            }
        } else {
            ItemStack armorToTrim = equipmentStack;
            if (operatingMode.get().equals(SmithingMode.Trim) && !isArmor(armorToTrim)) {
                error("Item in equipment slot was not armor..!");
                return null;
            }
            Item needed;
            if (operatingMode.get().equals(SmithingMode.Trim)) {
                needed = getNeededPatternItem(armorToTrim);
            } else {
                needed = Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE;
            }
            for (int n = 4; n < mc.player.currentScreenHandler.slots.size(); n++) {
                if (processedSlots.contains(n)) continue;
                ItemStack stack = handler.getSlot(n).getStack();
                if (stack.isOf(needed)) {
                    trimStack = stack;
                    processedSlots.add(n);
                    projectedEmpty.add(n);
                    processedSlots.add(SmithingScreenHandler.TEMPLATE_ID);
                    changedSlots.put(SmithingScreenHandler.TEMPLATE_ID, stack);
                    changedSlots.put(n, ItemStack.EMPTY);

                    if (operatingMode.get().equals(SmithingMode.Trim)) {
                        // todo: fabricate trim components for output stack if needed (currently not)

                        ItemStack output = new ItemStack(
                            stack.getItem().getRegistryEntry(),
                            stack.getCount(), stack.getComponentChanges()
                        );

                        changedSlots.put(SmithingScreenHandler.OUTPUT_ID, output);
                    } else if (equipmentStack != null) {
                        ItemStack output = getUpgradedItem(equipmentStack);
                        changedSlots.put(SmithingScreenHandler.OUTPUT_ID, output);
                    }

                    return buildSmithingPacket(handler, n, changedSlots);
                }
            }
        }

        if (debug.get()) {
            info("gen: no packet — needed item not found in inventory (eq="
                + (equipmentStack == null ? "-" : "set") + " mat=" + (materialStack == null ? "-" : "set")
                + " tmpl=" + (trimStack == null ? "-" : "set") + ").");
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private ItemStack getUpgradedItem(ItemStack original) {
        if (original.isOf(Items.DIAMOND_HELMET)) {
            return new ItemStack(
                Items.NETHERITE_HELMET.getRegistryEntry(),
                original.getCount(), original.getComponentChanges()
            );
        } else if (original.isOf(Items.DIAMOND_CHESTPLATE)) {
            return new ItemStack(
                Items.NETHERITE_CHESTPLATE.getRegistryEntry(),
                original.getCount(), original.getComponentChanges()
            );
        } else if (original.isOf(Items.DIAMOND_LEGGINGS)) {
            return new ItemStack(
                Items.NETHERITE_LEGGINGS.getRegistryEntry(),
                original.getCount(), original.getComponentChanges()
            );
        } else if (original.isOf(Items.DIAMOND_BOOTS)) {
            return new ItemStack(
                Items.NETHERITE_BOOTS.getRegistryEntry(),
                original.getCount(), original.getComponentChanges()
            );
        } else if (original.isOf(Items.DIAMOND_SWORD)) {
            return new ItemStack(
                Items.NETHERITE_SWORD.getRegistryEntry(),
                original.getCount(), original.getComponentChanges()
            );
        } else if (original.isOf(Items.DIAMOND_PICKAXE)) {
            return new ItemStack(
                Items.NETHERITE_PICKAXE.getRegistryEntry(),
                original.getCount(), original.getComponentChanges()
            );
        } else if (original.isOf(Items.DIAMOND_AXE)) {
            return new ItemStack(
                Items.NETHERITE_AXE.getRegistryEntry(),
                original.getCount(), original.getComponentChanges()
            );
        } else if (original.isOf(Items.DIAMOND_SHOVEL)) {
            return new ItemStack(
                Items.NETHERITE_SHOVEL.getRegistryEntry(),
                original.getCount(), original.getComponentChanges()
            );
        } else if (original.isOf(Items.DIAMOND_HOE)) {
            return new ItemStack(
                Items.NETHERITE_HOE.getRegistryEntry(),
                original.getCount(), original.getComponentChanges()
            );
        } else {
            return original;
        }
    }

    private EquipmentType computeLookingFor() {
        if (!exhaustedArmorTypes.contains(EquipmentType.HELMET)) return EquipmentType.HELMET;
        else if (!exhaustedArmorTypes.contains(EquipmentType.CHESTPLATE)) return EquipmentType.CHESTPLATE;
        else if (!exhaustedArmorTypes.contains(EquipmentType.LEGGINGS)) return EquipmentType.LEGGINGS;
        else return EquipmentType.BOOTS;
    }

    private int predictEmptySlot(SmithingScreenHandler handler) {
        if (mc.player == null) return -1;
        for (int n = mc.player.currentScreenHandler.slots.size() - 1; n >= 4; n--) {
            if (processedSlots.contains(n) && !projectedEmpty.contains(n)) continue;
            if (projectedEmpty.contains(n)) {
                projectedEmpty.rem(n);
                return n;
            } else if (handler.getSlot(n).getStack().isEmpty()) {
                processedSlots.add(n);
                return n;
            }
        }
        return -1;
    }

    private @Nullable Item getNeededPatternItem(ItemStack armorToTrim) {
        Item neededPattern = null;
        switch (getEquipmentType(armorToTrim)) {
            case BOOTS -> {
                switch (effectiveTrim(EquipmentType.BOOTS)) {
                    case Eye -> neededPattern = Items.EYE_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Rib -> neededPattern = Items.RIB_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Vex -> neededPattern = Items.VEX_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Dune -> neededPattern = Items.DUNE_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Host -> neededPattern = Items.HOST_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Tide -> neededPattern = Items.TIDE_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Ward -> neededPattern = Items.WARD_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Wild -> neededPattern = Items.WILD_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Bolt -> neededPattern = Items.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Flow -> neededPattern = Items.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Coast -> neededPattern = Items.COAST_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Snout -> neededPattern = Items.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Spire -> neededPattern = Items.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Raiser -> neededPattern = Items.RAISER_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Sentry -> neededPattern = Items.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Shaper -> neededPattern = Items.SHAPER_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Silence -> neededPattern = Items.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Wayfinder -> neededPattern = Items.WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE;
                }
            }
            case HELMET -> {
                switch (effectiveTrim(EquipmentType.HELMET)) {
                    case Eye -> neededPattern = Items.EYE_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Rib -> neededPattern = Items.RIB_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Vex -> neededPattern = Items.VEX_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Dune -> neededPattern = Items.DUNE_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Host -> neededPattern = Items.HOST_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Tide -> neededPattern = Items.TIDE_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Ward -> neededPattern = Items.WARD_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Wild -> neededPattern = Items.WILD_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Bolt -> neededPattern = Items.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Flow -> neededPattern = Items.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Coast -> neededPattern = Items.COAST_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Snout -> neededPattern = Items.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Spire -> neededPattern = Items.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Raiser -> neededPattern = Items.RAISER_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Sentry -> neededPattern = Items.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Shaper -> neededPattern = Items.SHAPER_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Silence -> neededPattern = Items.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Wayfinder -> neededPattern = Items.WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE;
                }
            }
            case LEGGINGS -> {
                switch (effectiveTrim(EquipmentType.LEGGINGS)) {
                    case Eye -> neededPattern = Items.EYE_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Rib -> neededPattern = Items.RIB_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Vex -> neededPattern = Items.VEX_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Dune -> neededPattern = Items.DUNE_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Host -> neededPattern = Items.HOST_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Tide -> neededPattern = Items.TIDE_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Ward -> neededPattern = Items.WARD_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Wild -> neededPattern = Items.WILD_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Bolt -> neededPattern = Items.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Flow -> neededPattern = Items.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Coast -> neededPattern = Items.COAST_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Snout -> neededPattern = Items.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Spire -> neededPattern = Items.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Raiser -> neededPattern = Items.RAISER_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Sentry -> neededPattern = Items.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Shaper -> neededPattern = Items.SHAPER_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Silence -> neededPattern = Items.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Wayfinder -> neededPattern = Items.WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE;
                }
            }
            case CHESTPLATE -> {
                switch (effectiveTrim(EquipmentType.CHESTPLATE)) {
                    case Eye -> neededPattern = Items.EYE_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Rib -> neededPattern = Items.RIB_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Vex -> neededPattern = Items.VEX_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Dune -> neededPattern = Items.DUNE_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Host -> neededPattern = Items.HOST_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Tide -> neededPattern = Items.TIDE_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Ward -> neededPattern = Items.WARD_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Wild -> neededPattern = Items.WILD_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Bolt -> neededPattern = Items.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Flow -> neededPattern = Items.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Coast -> neededPattern = Items.COAST_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Snout -> neededPattern = Items.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Spire -> neededPattern = Items.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Raiser -> neededPattern = Items.RAISER_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Sentry -> neededPattern = Items.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Shaper -> neededPattern = Items.SHAPER_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Silence -> neededPattern = Items.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE;
                    case Wayfinder -> neededPattern = Items.WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE;
                }
            }
        }

        return neededPattern;
    }

    private @Nullable Item getNeededMaterialItem(ItemStack armorToTrim) {
        Item neededMaterial = null;
        switch (getEquipmentType(armorToTrim)) {
            case BOOTS -> {
                switch (effectiveTrimMaterial(EquipmentType.BOOTS)) {
                    case Gold -> neededMaterial = Items.GOLD_INGOT;
                    case Iron -> neededMaterial = Items.IRON_INGOT;
                    case Lapis -> neededMaterial = Items.LAPIS_LAZULI;
                    case Resin -> neededMaterial = Items.RESIN_BRICK;
                    case Copper -> neededMaterial = Items.COPPER_INGOT;
                    case Quartz -> neededMaterial = Items.QUARTZ;
                    case Diamond -> neededMaterial = Items.DIAMOND;
                    case Emerald -> neededMaterial = Items.EMERALD;
                    case Amethyst -> neededMaterial = Items.AMETHYST_SHARD;
                    case Redstone -> neededMaterial = Items.REDSTONE;
                    case Netherite -> neededMaterial = Items.NETHERITE_INGOT;
                }
            }
            case HELMET -> {
                switch (effectiveTrimMaterial(EquipmentType.HELMET)) {
                    case Gold -> neededMaterial = Items.GOLD_INGOT;
                    case Iron -> neededMaterial = Items.IRON_INGOT;
                    case Lapis -> neededMaterial = Items.LAPIS_LAZULI;
                    case Resin -> neededMaterial = Items.RESIN_BRICK;
                    case Copper -> neededMaterial = Items.COPPER_INGOT;
                    case Quartz -> neededMaterial = Items.QUARTZ;
                    case Diamond -> neededMaterial = Items.DIAMOND;
                    case Emerald -> neededMaterial = Items.EMERALD;
                    case Amethyst -> neededMaterial = Items.AMETHYST_SHARD;
                    case Redstone -> neededMaterial = Items.REDSTONE;
                    case Netherite -> neededMaterial = Items.NETHERITE_INGOT;
                }
            }
            case LEGGINGS -> {
                switch (effectiveTrimMaterial(EquipmentType.LEGGINGS)) {
                    case Gold -> neededMaterial = Items.GOLD_INGOT;
                    case Iron -> neededMaterial = Items.IRON_INGOT;
                    case Lapis -> neededMaterial = Items.LAPIS_LAZULI;
                    case Resin -> neededMaterial = Items.RESIN_BRICK;
                    case Copper -> neededMaterial = Items.COPPER_INGOT;
                    case Quartz -> neededMaterial = Items.QUARTZ;
                    case Diamond -> neededMaterial = Items.DIAMOND;
                    case Emerald -> neededMaterial = Items.EMERALD;
                    case Amethyst -> neededMaterial = Items.AMETHYST_SHARD;
                    case Redstone -> neededMaterial = Items.REDSTONE;
                    case Netherite -> neededMaterial = Items.NETHERITE_INGOT;
                }
            }
            case CHESTPLATE -> {
                switch (effectiveTrimMaterial(EquipmentType.CHESTPLATE)) {
                    case Gold -> neededMaterial = Items.GOLD_INGOT;
                    case Iron -> neededMaterial = Items.IRON_INGOT;
                    case Lapis -> neededMaterial = Items.LAPIS_LAZULI;
                    case Resin -> neededMaterial = Items.RESIN_BRICK;
                    case Copper -> neededMaterial = Items.COPPER_INGOT;
                    case Quartz -> neededMaterial = Items.QUARTZ;
                    case Diamond -> neededMaterial = Items.DIAMOND;
                    case Emerald -> neededMaterial = Items.EMERALD;
                    case Amethyst -> neededMaterial = Items.AMETHYST_SHARD;
                    case Redstone -> neededMaterial = Items.REDSTONE;
                    case Netherite -> neededMaterial = Items.NETHERITE_INGOT;
                }
            }
        }

        return neededMaterial;
    }
}
