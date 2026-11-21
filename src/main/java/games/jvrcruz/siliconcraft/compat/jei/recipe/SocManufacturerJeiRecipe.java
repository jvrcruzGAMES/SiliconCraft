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
        ItemStack output,
        int energy,
        int time
) {}
