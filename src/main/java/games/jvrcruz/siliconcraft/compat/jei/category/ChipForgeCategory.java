package games.jvrcruz.siliconcraft.compat.jei.category;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.compat.jei.recipe.ChipForgeJeiRecipe;
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

public class ChipForgeCategory implements IRecipeCategory<ChipForgeJeiRecipe> {
    public static final RecipeType<ChipForgeJeiRecipe> RECIPE_TYPE =
            RecipeType.create(Siliconcraft.MODID, "chip_forge", ChipForgeJeiRecipe.class);

    private final IDrawable icon;
    private final IDrawableAnimated arrow;
    private final Component title;

    public ChipForgeCategory(IGuiHelper guiHelper) {
        this.title = Component.translatable("jei.siliconcraft.category.chip_forge");
        this.icon = guiHelper.createDrawableItemLike(ModBlocks.CHIP_FORGE.get());
        this.arrow = guiHelper.createAnimatedRecipeArrow(60);
    }

    @Override
    public RecipeType<ChipForgeJeiRecipe> getRecipeType() {
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
    public void setRecipe(IRecipeLayoutBuilder builder, ChipForgeJeiRecipe recipe, IFocusGroup focuses) {
        // Architecture Writeup (Slot 3)
        builder.addSlot(RecipeIngredientRole.INPUT, 4, 6)
                .addItemStack(recipe.architectureWriteup())
                .setStandardSlotBackground();

        // Silicon Powder (Slot 1)
        builder.addSlot(RecipeIngredientRole.INPUT, 24, 6)
                .addItemStack(recipe.silicon())
                .setStandardSlotBackground();

        // Redstone Dust (Slot 2)
        builder.addSlot(RecipeIngredientRole.INPUT, 44, 6)
                .addItemStack(recipe.redstone())
                .setStandardSlotBackground();

        // Output Chip (Slot 4)
        builder.addSlot(RecipeIngredientRole.OUTPUT, 110, 16)
                .addItemStack(recipe.output())
                .setOutputSlotBackground();
    }

    @Override
    public void draw(ChipForgeJeiRecipe recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics guiGraphics, double mouseX, double mouseY) {
        arrow.draw(guiGraphics, 72, 17);

        Minecraft mc = Minecraft.getInstance();
        guiGraphics.drawString(mc.font, recipe.archName(), 4, 34, 0x333333, false);
        guiGraphics.drawString(mc.font, "⚡ " + recipe.energy() + " FE", 4, 48, 0xE65100, false);
        guiGraphics.drawString(mc.font, "⏱ " + (recipe.time() / 20) + "s", 72, 48, 0x555555, false);
    }
}
