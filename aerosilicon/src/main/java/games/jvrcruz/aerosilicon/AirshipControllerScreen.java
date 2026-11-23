package games.jvrcruz.aerosilicon;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = AeroSilicon.ID, value = Dist.CLIENT)
final class AirshipControllerScreen extends AbstractContainerScreen<AirshipControllerMenu> {
    private static final ResourceLocation BACKGROUND = AeroSilicon.id("textures/gui/airship_controller.png");
    private final EditBox[] names = new EditBox[AirshipControllerEntity.CHANNELS];

    private AirshipControllerScreen(AirshipControllerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageHeight = 228;
        imageWidth = 196;
        inventoryLabelY = 134;
    }

    @SubscribeEvent
    public static void register(RegisterMenuScreensEvent event) {
        event.register(AeroSilicon.CONTROLLER_MENU.get(), AirshipControllerScreen::new);
    }

    @Override
    protected void init() {
        super.init();
        for (int i = 0; i < names.length; i++) {
            String previous = names[i] == null ? menu.channelName(i) : names[i].getValue();
            int row = i;
            int y = topPos + 36 + row * 24;
            EditBox field = new EditBox(font, leftPos + 8, y, 94, 19,
                    Component.translatable("screen.aerosilicon.nickname"));
            field.setMaxLength(AirshipControllerEntity.MAX_NAME);
            field.setValue(previous);
            names[i] = addRenderableWidget(field);
            addRenderableWidget(Button.builder(Component.translatable("screen.aerosilicon.save"),
                    button -> PacketDistributor.sendToServer(new RenameChannelPayload(menu.containerId, row, field.getValue())))
                    .bounds(leftPos + 151, y, 37, 19).build());
        }
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key != GLFW.GLFW_KEY_ESCAPE && key != GLFW.GLFW_KEY_TAB && getFocused() instanceof EditBox field) {
            field.keyPressed(key, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        if (hoveredSlot != null && hoveredSlot.index < names.length * 2 && !hoveredSlot.hasItem()
                && menu.getCarried().isEmpty()) {
            graphics.renderTooltip(font, Component.translatable("screen.aerosilicon.frequency_slot"), mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(BACKGROUND, leftPos, topPos, 0f, 0f, imageWidth, imageHeight, imageWidth, imageHeight);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 8, 0xFFF4F8FB, false);
        graphics.drawString(font, Component.translatable("screen.aerosilicon.nickname"), 8, 24, 0xFFBBCBD4, false);
        graphics.drawString(font, Component.translatable("screen.aerosilicon.frequencies"), 107, 24, 0xFFBBCBD4, false);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xFFBBCBD4, false);
        for (int i = 0; i < names.length; i++) {
            if (menu.isPowered(i)) graphics.fill(147, 42 + i * 24, 150, 49 + i * 24, 0xFF7ADBAC);
        }
    }
}
