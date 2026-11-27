package games.jvrcruz.siliconcraft.client.renderer;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.joml.Matrix4f;

public class ComputerDisplayRenderer {
    public static final int WIDTH = VirtualCpu.FB_WIDTH;
    public static final int HEIGHT = VirtualCpu.FB_HEIGHT;
    private final DynamicTexture dynamicTexture;
    private final NativeImage nativeImage;

    public ComputerDisplayRenderer() {
        this.nativeImage = new NativeImage(WIDTH, HEIGHT, false);
        this.dynamicTexture = new DynamicTexture(this.nativeImage);
    }

    public void updatePixels(int[] pixels) {
        if (pixels == null) return;
        int len = Math.min(pixels.length, WIDTH * HEIGHT);
        int i = 0;
        for (int y = 0; y < HEIGHT && i < len; y++) {
            for (int x = 0; x < WIDTH && i < len; x++, i++) {
                int argb = pixels[i];
                int a = (argb >> 24) & 0xFF;
                if (a == 0) a = 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                // NativeImage setPixelRGBA expects ABGR: (a << 24) | (b << 16) | (g << 8) | r
                int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                nativeImage.setPixelRGBA(x, y, abgr);
            }
        }
        dynamicTexture.upload();
    }

    public void renderDirect(Matrix4f matrix, float x0, float y0, float x1, float y1) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, dynamicTexture.getId());
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buffer.addVertex(matrix, x0, y1, 0.0f).setUv(0.0f, 1.0f);
        buffer.addVertex(matrix, x1, y1, 0.0f).setUv(1.0f, 1.0f);
        buffer.addVertex(matrix, x1, y0, 0.0f).setUv(1.0f, 0.0f);
        buffer.addVertex(matrix, x0, y0, 0.0f).setUv(0.0f, 0.0f);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    public void close() {
        dynamicTexture.close();
    }
}
