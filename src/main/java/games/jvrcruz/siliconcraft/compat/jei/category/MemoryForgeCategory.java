package games.jvrcruz.siliconcraft.compat.jei.category;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.compat.jei.recipe.MemoryForgeJeiRecipe;
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

public class MemoryForgeCategory implements IRecipeCategory<MemoryForgeJeiRecipe> {
    public static final RecipeType<MemoryForgeJeiRecipe> RECIPE_TYPE =
            RecipeType.create(Siliconcraft.MODID, "memory_forge", MemoryForgeJeiRecipe.class);

    private final IDrawable icon;
    private final IDrawableAnimated arrow;
    private final Component title;

    public MemoryForgeCategory(IGuiHelper guiHelper) {
        this.title = Component.translatable("jei.siliconcraft.category.memory_forge");
        this.icon = guiHelper.createDrawableItemLike(ModBlocks.MEMORY_FORGE.get());
        this.arrow = guiHelper.createAnimatedRecipeArrow(60);
    }

    @Override
    public RecipeType<MemoryForgeJeiRecipe> getRecipeType() {
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
        return 64;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, MemoryForgeJeiRecipe recipe, IFocusGroup focuses) {
        int x = 6;
        for (int i = 0; i < recipe.inputs().size(); i++) {
            builder.addSlot(RecipeIngredientRole.INPUT, x, 14)
                    .addItemStack(recipe.inputs().get(i))
                    .setStandardSlotBackground();
            x += 20;
        }

        builder.addSlot(RecipeIngredientRole.OUTPUT, 110, 14)
                .addItemStack(recipe.output())
                .setOutputSlotBackground();
    }

    @Override
    public void draw(MemoryForgeJeiRecipe recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics guiGraphics, double mouseX, double mouseY) {
        arrow.draw(guiGraphics, 75, 15);

        Minecraft mc = Minecraft.getInstance();
        guiGraphics.drawString(mc.font, recipe.modeName(), 6, 2, 0x222222, false);
        guiGraphics.drawString(mc.font, "⚡ " + recipe.energy() + " FE", 6, 48, 0xE65100, false);
        guiGraphics.drawString(mc.font, "⏱ " + (recipe.time() / 20) + "s", 75, 48, 0x555555, false);
    }
}
