package games.jvrcruz.siliconcraft.compat.jei.category;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.compat.jei.recipe.ComputerAssemblyJeiRecipe;
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

public class ComputerAssemblyCategory implements IRecipeCategory<ComputerAssemblyJeiRecipe> {
    public static final RecipeType<ComputerAssemblyJeiRecipe> RECIPE_TYPE =
            RecipeType.create(Siliconcraft.MODID, "computer_assembly", ComputerAssemblyJeiRecipe.class);

    private final IDrawable icon;
    private final IDrawableAnimated arrow;
    private final Component title;

    public ComputerAssemblyCategory(IGuiHelper guiHelper) {
        this.title = Component.translatable("jei.siliconcraft.category.computer_assembly");
        this.icon = guiHelper.createDrawableItemLike(ModBlocks.COMPUTER.get());
        this.arrow = guiHelper.createAnimatedRecipeArrow(40);
    }

    @Override
    public RecipeType<ComputerAssemblyJeiRecipe> getRecipeType() {
        return RECIPE_TYPE;
    }

    @Override
    public Component getTitle() {
        return title;
    }

    @Override
    public int getWidth() {
        return 140;
    }

    @Override
    public int getHeight() {
        return 56;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, ComputerAssemblyJeiRecipe recipe, IFocusGroup focuses) {
        builder.addSlot(RecipeIngredientRole.INPUT, 10, 10)
                .addItemStack(recipe.computerCase())
                .setStandardSlotBackground();

        builder.addSlot(RecipeIngredientRole.INPUT, 36, 10)
                .addItemStack(recipe.soc())
                .setStandardSlotBackground();

        builder.addSlot(RecipeIngredientRole.OUTPUT, 106, 10)
                .addItemStack(recipe.computer())
                .setOutputSlotBackground();
    }

    @Override
    public void draw(ComputerAssemblyJeiRecipe recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics guiGraphics, double mouseX, double mouseY) {
        arrow.draw(guiGraphics, 66, 11);

        Minecraft mc = Minecraft.getInstance();
        guiGraphics.drawString(mc.font, "Right-click Case with SoC", 8, 38, 0x444444, false);
    }
}
