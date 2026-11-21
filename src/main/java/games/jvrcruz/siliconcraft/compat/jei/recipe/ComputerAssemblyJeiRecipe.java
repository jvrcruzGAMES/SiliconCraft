package games.jvrcruz.siliconcraft.compat.jei.recipe;

import net.minecraft.world.item.ItemStack;

public record ComputerAssemblyJeiRecipe(
        ItemStack computerCase,
        ItemStack soc,
        ItemStack computer
) {}
