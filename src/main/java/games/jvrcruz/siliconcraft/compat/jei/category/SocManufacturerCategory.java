package games.jvrcruz.siliconcraft.compat.jei.category;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.compat.jei.recipe.SocManufacturerJeiRecipe;
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

public class SocManufacturerCategory implements IRecipeCategory<SocManufacturerJeiRecipe> {
    public static final RecipeType<SocManufacturerJeiRecipe> RECIPE_TYPE =
            RecipeType.create(Siliconcraft.MODID, "soc_manufacturer", SocManufacturerJeiRecipe.class);

    private final IDrawable icon;
    private final IDrawableAnimated arrow;
    private final Component title;

    public SocManufacturerCategory(IGuiHelper guiHelper) {
        this.title = Component.translatable("jei.siliconcraft.category.soc_manufacturer");
        this.icon = guiHelper.createDrawableItemLike(ModBlocks.SOC_MANUFACTURER.get());
        this.arrow = guiHelper.createAnimatedRecipeArrow(60);
    }

    @Override
    public RecipeType<SocManufacturerJeiRecipe> getRecipeType() {
        return RECIPE_TYPE;
    }

    @Override
    public Component getTitle() {
        return title;
    }

    @Override
    public int getWidth() {
        return 146;
    }

    @Override
    public int getHeight() {
        return 68;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, SocManufacturerJeiRecipe recipe, IFocusGroup focuses) {
        // Wooden slab substrate
        builder.addSlot(RecipeIngredientRole.INPUT, 4, 6)
                .addIngredients(recipe.substrate())
                .setStandardSlotBackground();

        // Silicon powder
        builder.addSlot(RecipeIngredientRole.INPUT, 24, 6)
                .addItemStack(recipe.silicon())
                .setStandardSlotBackground();

        // Gold ingot
        builder.addSlot(RecipeIngredientRole.INPUT, 44, 6)
                .addItemStack(recipe.gold())
                .setStandardSlotBackground();

        // ROM chip
        builder.addSlot(RecipeIngredientRole.INPUT, 4, 26)
                .addItemStack(recipe.rom())
                .setStandardSlotBackground();

        // CPU chip
        builder.addSlot(RecipeIngredientRole.INPUT, 24, 26)
                .addItemStack(recipe.chip())
                .setStandardSlotBackground();

        // RAM module
        builder.addSlot(RecipeIngredientRole.INPUT, 44, 26)
                .addItemStack(recipe.ram())
                .setStandardSlotBackground();

        // Optional Storage drive
        if (!recipe.storage().isEmpty()) {
            builder.addSlot(RecipeIngredientRole.INPUT, 64, 26)
                    .addItemStack(recipe.storage())
                    .setStandardSlotBackground();
        }

        // Optional GPU
        if (!recipe.gpu().isEmpty()) {
            builder.addSlot(RecipeIngredientRole.INPUT, 64, 6)
                    .addItemStack(recipe.gpu())
                    .setStandardSlotBackground();
        }

        // Output SoC
        builder.addSlot(RecipeIngredientRole.OUTPUT, 118, 16)
                .addItemStack(recipe.output())
                .setOutputSlotBackground();
    }

    @Override
    public void draw(SocManufacturerJeiRecipe recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics guiGraphics, double mouseX, double mouseY) {
        arrow.draw(guiGraphics, 86, 17);

        Minecraft mc = Minecraft.getInstance();
        guiGraphics.drawString(mc.font, "⚡ " + recipe.energy() + " FE", 4, 52, 0xE65100, false);
        guiGraphics.drawString(mc.font, "⏱ " + (recipe.time() / 20) + "s", 78, 52, 0x555555, false);
    }
}
