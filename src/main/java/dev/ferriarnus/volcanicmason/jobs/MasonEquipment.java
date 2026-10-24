package dev.ferriarnus.volcanicmason.jobs;

import com.minecolonies.api.equipment.ModEquipmentTypes;
import com.minecolonies.api.equipment.registry.EquipmentTypeEntry;
import com.minecolonies.api.util.constant.translation.ToolTranslationConstants;
import dev.ferriarnus.volcanicmason.VolcanicMasonMod;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Worker-local equipment category for 26.1's component-based pickaxes.
 * The current colony port still checks the removed pickaxe_dig ability.
 * A separate entry preserves colony tool requests without changing its global pickaxe category.
 */
public final class MasonEquipment {
    private static final DeferredRegister<EquipmentTypeEntry> TYPES = DeferredRegister.create(
        Identifier.fromNamespaceAndPath("minecolonies", "equipmenttypes"), VolcanicMasonMod.MODID);

    public static final DeferredHolder<EquipmentTypeEntry, EquipmentTypeEntry> PICKAXE = TYPES.register("pickaxe",
        () -> new EquipmentTypeEntry.Builder()
            .setRegistryName(Identifier.fromNamespaceAndPath(VolcanicMasonMod.MODID, "pickaxe"))
            .setDisplayName(Component.translatable(ToolTranslationConstants.TOOL_TYPE_PICKAXE))
            .setIsEquipment((stack, type) -> (stack.is(ItemTags.PICKAXES) && stack.has(DataComponents.TOOL))
                || ModEquipmentTypes.pickaxe.get().checkIsEquipment(stack))
            .setEquipmentLevel(ModEquipmentTypes::vanillaToolLevel)
            .build());

    private MasonEquipment() {}

    public static void register(IEventBus bus) {
        TYPES.register(bus);
    }
}
