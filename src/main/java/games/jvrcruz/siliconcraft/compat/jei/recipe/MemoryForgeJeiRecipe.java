package games.jvrcruz.siliconcraft.compat.jei.recipe;

import net.minecraft.world.item.ItemStack;

import java.util.List;

public record MemoryForgeJeiRecipe(
        String modeName,
        List<ItemStack> inputs,
        ItemStack output,
        int energy,
        int time
) {}
