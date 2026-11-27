package games.jvrcruz.siliconcraft.compat.jei.recipe;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

public record SocManufacturerJeiRecipe(
        Ingredient substrate,
        ItemStack silicon,
        ItemStack gold,
        ItemStack rom,
        ItemStack chip,
        ItemStack ram,
        ItemStack storage,
        ItemStack gpu,
        ItemStack output,
        int energy,
        int time
) {
    public SocManufacturerJeiRecipe(
            Ingredient substrate,
            ItemStack silicon,
            ItemStack gold,
            ItemStack rom,
            ItemStack chip,
            ItemStack ram,
            ItemStack storage,
            ItemStack output,
            int energy,
            int time
    ) {
        this(substrate, silicon, gold, rom, chip, ram, storage, ItemStack.EMPTY, output, energy, time);
    }
}
