package games.jvrcruz.siliconcraft.compat.jei.recipe;

import net.minecraft.world.item.ItemStack;

public record GpuAssemblerJeiRecipe(
        String label,
        ItemStack gpuWriteup,
        ItemStack silicon,
        ItemStack redstone,
        ItemStack ramModules,
        ItemStack output,
        int energy,
        int time
) {}
