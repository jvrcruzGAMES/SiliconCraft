package games.jvrcruz.siliconcraft.compat.jei.category;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.compat.jei.recipe.GpuAssemblerJeiRecipe;
import games.jvrcruz.siliconcraft.init.ModBlocks;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.drawable.IDrawableAnimated;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public class GpuAssemblerCategory implements IRecipeCategory<GpuAssemblerJeiRecipe> {
    public static final RecipeType<GpuAssemblerJeiRecipe> RECIPE_TYPE =
            RecipeType.create(Siliconcraft.MODID, "gpu_assembler", GpuAssemblerJeiRecipe.class);

    private final IDrawable icon;
    private final IDrawableAnimated arrow;
    private final Component title;

    public GpuAssemblerCategory(IGuiHelper guiHelper) {
        this.title = Component.translatable("jei.siliconcraft.category.gpu_assembler");
        this.icon = guiHelper.createDrawableItemLike(ModBlocks.GPU_ASSEMBLER.get());
        this.arrow = guiHelper.createAnimatedRecipeArrow(60);
    }

    @Override
    public RecipeType<GpuAssemblerJeiRecipe> getRecipeType() {
        return RECIPE_TYPE;
    }

    @Override
    public Component getTitle() {
        return title;
    }

    @Override
    public int getWidth() {
        return 154;
    }

    @Override
    public int getHeight() {
        return 64;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, GpuAssemblerJeiRecipe recipe, IFocusGroup focuses) {
        builder.addSlot(RecipeIngredientRole.INPUT, 4, 16)
                .addItemStack(recipe.gpuWriteup())
                .setStandardSlotBackground();

        builder.addSlot(RecipeIngredientRole.INPUT, 24, 16)
                .addItemStack(recipe.silicon())
                .setStandardSlotBackground();

        builder.addSlot(RecipeIngredientRole.INPUT, 44, 16)
                .addItemStack(recipe.redstone())
                .setStandardSlotBackground();

        if (!recipe.ramModules().isEmpty()) {
            builder.addSlot(RecipeIngredientRole.INPUT, 64, 16)
                    .addItemStack(recipe.ramModules())
                    .setStandardSlotBackground();
        }

        builder.addSlot(RecipeIngredientRole.OUTPUT, 124, 16)
                .addItemStack(recipe.output())
                .setOutputSlotBackground();
    }

    @Override
    public void draw(GpuAssemblerJeiRecipe recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics guiGraphics, double mouseX, double mouseY) {
        arrow.draw(guiGraphics, 88, 17);

        Minecraft mc = Minecraft.getInstance();
        guiGraphics.drawString(mc.font, recipe.label(), 4, 2, 0x333333, false);
        guiGraphics.drawString(mc.font, "⚡ " + recipe.energy() + " FE", 4, 48, 0xE65100, false);
        guiGraphics.drawString(mc.font, "⏱ " + (recipe.time() / 20) + "s", 84, 48, 0x555555, false);
    }
}
