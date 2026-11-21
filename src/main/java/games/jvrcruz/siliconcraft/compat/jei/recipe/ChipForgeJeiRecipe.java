package games.jvrcruz.siliconcraft.compat.jei.recipe;

import net.minecraft.world.item.ItemStack;

import java.util.List;

public record ChipForgeJeiRecipe(
        ItemStack architectureWriteup,
        ItemStack silicon,
        ItemStack redstone,
        ItemStack output,
        int energy,
        int time,
        String archName,
        List<String> instructions
) {}
