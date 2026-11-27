package games.jvrcruz.siliconcraft;

import games.jvrcruz.siliconcraft.compiler.Assembler;
import games.jvrcruz.siliconcraft.compiler.InstructionValidator;
import games.jvrcruz.siliconcraft.compiler.n2.N2Compiler;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import games.jvrcruz.siliconcraft.vm.VirtualGpu;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VirtualGpuTest {

    private static VirtualCpu configuredCpu(byte[] program, int ramInts, int gpuRamModules) {
        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, ramInts, program, false, 0);
        if (gpuRamModules >= 0) {
            cpu.configureGpu(true, "Silicon-GPU",
                    gpuRamModules * VirtualGpu.RAM_MODULE_BYTES,
                    Math.max(1, 8 + gpuRamModules));
        } else {
            cpu.configureGpu(false, "", 0, 0);
        }
        cpu.powerOn();
        return cpu;
    }

    @Test
    void virtualGpuRasterizesPrimitivesAlphaBlendsVramAndDoubleBuffers() {
        VirtualGpu gpu = new VirtualGpu();
        gpu.configureHardware(true, "Silicon-GPU", VirtualGpu.RAM_MODULE_BYTES, 9);
        gpu.powerOn();

        assertTrue(gpu.isInstalled());
        assertEquals(VirtualGpu.State.IDLE, gpu.getState());

        // Clear back buffer, draw rectangle, circle, line, triangle, and upload sprite to VRAM
        gpu.submitClear(0xFF102030);
        gpu.submitFillRect(10, 10, 20, 12, 0xFFFF0000);
        gpu.submitDrawLine(0, 0, 10, 10, 0xFF00FF00);
        gpu.submitDrawCircle(100, 100, 8, 0xFF0000FF, true);
        gpu.submitDrawCircle(140, 100, 6, 0xFFFFFFFF, false);
        gpu.submitFillTriangle(200, 20, 220, 20, 210, 40, 0xFFFFFF00);

        // Front buffer must stay untouched until swap
        assertEquals(VirtualGpu.DEFAULT_BACKGROUND, gpu.getFrontBuffer()[15 * VirtualGpu.FB_WIDTH + 15]);

        // Upload 2x1 semi-transparent sprite (50% alpha white + fully transparent pixel)
        int[] ram = new int[16];
        ram[0] = 0x80FFFFFF;
        ram[1] = 0x00000000;
        gpu.uploadVram(0, ram, 0, 2);
        gpu.submitBlitFromVram(12, 12, 0, 2, 1, true);

        // Swap presents back buffer to front buffer
        gpu.submitSwap();

        assertEquals(1, gpu.getFramesPresented());
        assertEquals(1, gpu.getDisplayMode());
        assertTrue(gpu.isFbDirty());

        int[] front = gpu.getFrontBuffer();
        assertEquals(0xFF102030, front[5 * VirtualGpu.FB_WIDTH + 400]);
        assertEquals(0xFFFF0000, front[15 * VirtualGpu.FB_WIDTH + 15]);
        assertEquals(0xFF00FF00, front[5 * VirtualGpu.FB_WIDTH + 5]);
        assertEquals(0xFF0000FF, front[100 * VirtualGpu.FB_WIDTH + 100]);
        assertEquals(0xFFFFFFFF, front[100 * VirtualGpu.FB_WIDTH + 146]);
        assertEquals(0xFFFFFF00, front[28 * VirtualGpu.FB_WIDTH + 210]);

        // Transparent sprite pixel (13, 12) leaves red background untouched; 50% white blends toward pinkish
        assertEquals(0xFFFF0000, front[12 * VirtualGpu.FB_WIDTH + 13]);
        int blended = front[12 * VirtualGpu.FB_WIDTH + 12];
        int gChannel = (blended >> 8) & 0xFF;
        assertTrue(gChannel >= 120 && gChannel <= 136, "Expected ~50% alpha blended green channel, got " + gChannel);
    }

    @Test
    void virtualGpuRejectsOutOfBoundsVramAndHotPlugPreservesIdenticalConfig() {
        VirtualGpu gpu = new VirtualGpu();
        gpu.configureHardware(true, "Silicon-GPU", VirtualGpu.RAM_MODULE_BYTES, 9);
        gpu.powerOn();

        assertTrue(gpu.isSameConfigHardware(true, "Silicon-GPU", VirtualGpu.RAM_MODULE_BYTES, 9));
        assertFalse(gpu.isSameConfigHardware(true, "Silicon-GPU", 2 * VirtualGpu.RAM_MODULE_BYTES, 10));
        assertFalse(gpu.isSameConfigHardware(false, "", 0, 0));

        int[] ram = new int[]{0xFF112233};
        int capInts = gpu.getVramCapacityInts();
        assertThrows(IndexOutOfBoundsException.class, () -> gpu.uploadVram(capInts, ram, 0, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> gpu.submitBlitFromVram(0, 0, capInts, 2, 2, true));
    }

    @Test
    void graphicsHandlerSelectionRoutesBetweenCpuFramebufferAndVirtualGpu() {
        var assembled = Assembler.assemble(List.of(
                // Read initial stats in AUTO mode (with GPU installed)
                "GFXSTAT [0], 0",   // hasGpu -> 1
                "GFXSTAT [1], 1",   // isUsingGpu -> 1
                "GFXSTAT [2], 2",   // handler -> AUTO (0)
                "FBCLR 0xFF111111", // should draw on GPU under AUTO
                "FBSWAP",
                // Switch to CPU explicitly
                "GFXSEL 1",
                "GFXSTAT [3], 1",   // isUsingGpu -> 0
                "GFXSTAT [4], 2",   // handler -> CPU (1)
                "FBCLR 0xFF222222", // should draw on CPU framebuffer
                "FBRECT 2, 2, 4, 4, 0xFF334455",
                "FBSWAP",
                // Switch explicitly to GPU and draw another rect
                "GFXSEL 2",
                "GFXSTAT [5], 1",   // isUsingGpu -> 1
                "FBRECT 20, 20, 5, 5, 0xFF667788",
                "GPUSWAP",
                "HLT"
        ));
        assertTrue(assembled.success(), assembled.errors().toString());

        VirtualCpu cpu = configuredCpu(assembled.bytecode(), 256, 2);
        cpu.tick(200);
        assertEquals(VirtualCpu.State.HALTED, cpu.getState(), cpu.getErrorMessage());

        int[] ram = cpu.getRam();
        assertEquals(1, ram[0]); // hasGpu
        assertEquals(1, ram[1]); // isUsingGpu under AUTO
        assertEquals(VirtualCpu.GraphicsHandler.AUTO.ordinal(), ram[2]);
        assertEquals(0, ram[3]); // isUsingGpu under CPU
        assertEquals(VirtualCpu.GraphicsHandler.CPU.ordinal(), ram[4]);
        assertEquals(1, ram[5]); // isUsingGpu under GPU

        // CPU framebuffer got CPU clear/rect
        assertEquals(0xFF222222, cpu.getFramebuffer()[0]);
        assertEquals(0xFF334455, cpu.getFramebuffer()[3 * VirtualCpu.FB_WIDTH + 3]);

        // GPU front buffer got AUTO clear + GPU rect, unaffected by CPU-mode FBCLR
        assertEquals(0xFF111111, cpu.getGpu().getFrontBuffer()[0]);
        assertEquals(0xFF667788, cpu.getGpu().getFrontBuffer()[22 * VirtualCpu.FB_WIDTH + 22]);
        assertSame(cpu.getGpu().getFrontBuffer(), cpu.getActiveFramebuffer());

        // Switching handler from UI/host to CPU exposes CPU framebuffer immediately
        cpu.cycleGraphicsHandler(); // GPU -> AUTO
        assertEquals(VirtualCpu.GraphicsHandler.AUTO, cpu.getGraphicsHandler());
        cpu.cycleGraphicsHandler(); // AUTO -> CPU
        assertEquals(VirtualCpu.GraphicsHandler.CPU, cpu.getGraphicsHandler());
        assertSame(cpu.getFramebuffer(), cpu.getActiveFramebuffer());
    }

    @Test
    void glassAutomaticallyUsesCpuFallbackWithoutGpuAndFaultsOnDirectGpuOpcodes() {
        var fbProgram = Assembler.assemble(List.of(
                "GFXSTAT [0], 0",
                "GFXSTAT [1], 1",
                "FBCLR 0xFFABCDEF",
                "FBSWAP",
                "HLT"
        ));
        assertTrue(fbProgram.success(), fbProgram.errors().toString());

        VirtualCpu noGpuCpu = configuredCpu(fbProgram.bytecode(), 256, -1);
        noGpuCpu.tick(100);
        assertEquals(VirtualCpu.State.HALTED, noGpuCpu.getState(), noGpuCpu.getErrorMessage());
        assertEquals(0, noGpuCpu.getRam()[0]);
        assertEquals(0, noGpuCpu.getRam()[1]);
        assertEquals(0xFFABCDEF, noGpuCpu.getFramebuffer()[0]);
        assertSame(noGpuCpu.getFramebuffer(), noGpuCpu.getActiveFramebuffer());

        // Direct GPU hardware instruction without GPU triggers exception
        var gpuProgram = Assembler.assemble(List.of("GPUCLR 0xFFFFFFFF", "HLT"));
        assertTrue(gpuProgram.success(), gpuProgram.errors().toString());
        VirtualCpu faultCpu = configuredCpu(gpuProgram.bytecode(), 256, -1);
        faultCpu.tick(20);
        assertEquals(VirtualCpu.State.ERROR, faultCpu.getState());
        assertTrue(faultCpu.getErrorMessage().contains("GPU"), faultCpu.getErrorMessage());
    }

    @Test
    void n2GlassLibraryAutoSelectsGpuAndCompilesAcceleratedPrimitives() {
        var compiled = N2Compiler.compile("""
                import <Glass>
                import <std>

                func main()
                    println(has_gpu())
                    println(is_using_gpu())
                    println(gpu_vram_kb())

                    clear_screen(COLOR_BLUE)
                    draw_rect(10, 10, 12, 12, COLOR_RED)
                    fill_circle(80, 60, 10, COLOR_GREEN)
                    draw_circle(120, 60, 5, COLOR_WHITE)
                    fill_triangle(160, 10, 180, 10, 170, 30, COLOR_YELLOW)

                    local sprite = [COLOR_CYAN, COLOR_TRANSPARENT, COLOR_TRANSPARENT, COLOR_MAGENTA]
                    vram_upload(0, sprite + 2, 4)
                    gpu_blit(50, 50, 0, 2, 2)
                    gpu_swap()
                    gpu_sync()

                    use_cpu()
                    println(is_using_gpu())
                    clear_screen(COLOR_BLACK)
                    swap()

                    use_gpu()
                    println(is_using_gpu())
                end
                """);
        assertTrue(compiled.success(), compiled.errors().toString());

        var assembled = Assembler.assemble(compiled.assembly());
        assertTrue(assembled.success(), assembled.errors().toString());
        assertTrue(assembled.requiredInstructions().contains("GPUCIRC"));
        assertTrue(assembled.requiredInstructions().contains("GPUTRI"));
        assertTrue(assembled.requiredInstructions().contains("GPUVRAM"));
        assertTrue(assembled.requiredInstructions().contains("GPUBLIT"));
        assertTrue(assembled.requiredInstructions().contains("GFXSEL"));
        assertTrue(assembled.requiredInstructions().contains("GFXSTAT"));

        VirtualCpu cpu = configuredCpu(assembled.bytecode(), 65536, 1);
        cpu.tick(50000);
        assertEquals(VirtualCpu.State.HALTED, cpu.getState(), cpu.getErrorMessage());

        var nums = cpu.getTerminalOutput().stream().filter(s -> s.matches("-?\\d+")).toList();
        assertEquals(List.of("1", "1", "2048", "0", "1"), nums);

        int[] gpuFront = cpu.getGpu().getFrontBuffer();
        assertEquals(0xFF0000FF, gpuFront[2 * VirtualGpu.FB_WIDTH + 2]);
        assertEquals(0xFFFF0000, gpuFront[15 * VirtualGpu.FB_WIDTH + 15]);
        assertEquals(0xFF00FF00, gpuFront[60 * VirtualGpu.FB_WIDTH + 80]);
        assertEquals(0xFFFFFFFF, gpuFront[60 * VirtualGpu.FB_WIDTH + 125]);
        assertEquals(0xFFFFFF00, gpuFront[18 * VirtualGpu.FB_WIDTH + 170]);
        assertEquals(0xFF00FFFF, gpuFront[50 * VirtualGpu.FB_WIDTH + 50]);
        assertEquals(0xFFFF00FF, gpuFront[51 * VirtualGpu.FB_WIDTH + 51]);

        // CPU mode clear_screen wrote black ONLY to CPU framebuffer, preserving GPU front buffer
        assertEquals(0xFF000000, cpu.getFramebuffer()[0]);
    }

    @Test
    void n2GlassRejectsInvalidArityAndGpuCostHelpersWork() {
        for (String bad : List.of("draw_circle(1, 2, 3)", "fill_triangle(1, 2, 3)", "vram_upload(0, 1)", "has_gpu(1)")) {
            var res = N2Compiler.compile("import <Glass>\nfunc main()\n    " + bad + "\nend\n");
            assertFalse(res.success(), "Expected compile error for: " + bad);
            assertTrue(res.errors().stream().anyMatch(e -> e.contains("expects")), res.errors().toString());
        }

        // 0..8 RAM modules cost scaling
        assertEquals(600, VirtualGpu.calculateRequiredEnergy(0));
        assertEquals(60, VirtualGpu.calculateMaxProgress(0));
        assertEquals(2600, VirtualGpu.calculateRequiredEnergy(8));
        assertEquals(220, VirtualGpu.calculateMaxProgress(8));
        assertEquals(2600, VirtualGpu.calculateRequiredEnergy(99));

        assertEquals("0 Bytes", VirtualGpu.formatVram(0));
        assertEquals("2 MB", VirtualGpu.formatVram(2048 * 1024));
    }

    @Test
    void gpuInstructionPoolCostScalingAndFeatureGatingWork() {
        assertEquals(19, InstructionValidator.GPU_INSTRUCTIONS.size());
        assertTrue(InstructionValidator.GPU_INSTRUCTIONS_SET.containsAll(List.of(
                "FBCLR", "FBSWAP", "GPUCLR", "GPURECT", "GPULINE", "GPUCIRC", "GPUTRI", "GPUVRAM", "GPUBLIT", "GPUSWAP", "GPUSYNC"
        )));

        // Silicon, Redstone, energy, and time scale with GPU opcode count and RAM modules
        assertEquals(1, VirtualGpu.calculateRequiredSilicon(2));
        assertEquals(1, VirtualGpu.calculateRequiredRedstone(2));
        assertEquals(5, VirtualGpu.calculateRequiredSilicon(19));
        assertEquals(7, VirtualGpu.calculateRequiredRedstone(19));
        assertEquals(200 + 19 * 60 + 2 * 250, VirtualGpu.calculateRequiredEnergy(19, 2));
        assertEquals(40 + 19 * 8 + 2 * 20, VirtualGpu.calculateMaxProgress(19, 2));

        // Restricted GPU feature set allows included GPU opcodes and rejects omitted ones
        var okProgram = Assembler.assemble(List.of("GPUCLR 0xFF112233", "GPUSWAP", "HLT"));
        assertTrue(okProgram.success(), okProgram.errors().toString());
        VirtualCpu cpu = new VirtualCpu();
        cpu.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 256, okProgram.bytecode(), false, 0);
        cpu.getGpu().configureHardware(true, "Lite-GPU", VirtualGpu.RAM_MODULE_BYTES, 2, List.of("GPUCLR", "GPUSWAP"));
        cpu.powerOn();
        assertTrue(cpu.getGpu().supportsFeature("GPUCLR"));
        assertFalse(cpu.getGpu().supportsFeature("GPUCIRC"));
        cpu.tick(100);
        assertEquals(VirtualCpu.State.HALTED, cpu.getState(), cpu.getErrorMessage());
        assertEquals(0xFF112233, cpu.getGpu().getFrontBuffer()[0]);

        // Hot-swapping GPU with different features reports different hardware config
        assertTrue(cpu.getGpu().isSameConfigHardware(true, "Lite-GPU", VirtualGpu.RAM_MODULE_BYTES, 2, List.of("GPUCLR", "GPUSWAP")));
        assertFalse(cpu.getGpu().isSameConfigHardware(true, "Lite-GPU", VirtualGpu.RAM_MODULE_BYTES, 2, List.of("GPUCLR", "GPUCIRC", "GPUSWAP")));

        // Executing GPUCIRC on Lite-GPU lacking GPUCIRC must fault cleanly
        var missingFeatureProgram = Assembler.assemble(List.of("GPUCIRC 100, 100, 10, 0xFFFFFFFF", "HLT"));
        assertTrue(missingFeatureProgram.success());
        VirtualCpu faultCpu = new VirtualCpu();
        faultCpu.configure(InstructionValidator.ALL_INSTRUCTIONS_SET, 256, missingFeatureProgram.bytecode(), false, 0);
        faultCpu.getGpu().configureHardware(true, "Lite-GPU", VirtualGpu.RAM_MODULE_BYTES, 2, List.of("GPUCLR", "GPUSWAP"));
        faultCpu.powerOn();
        faultCpu.tick(20);
        assertEquals(VirtualCpu.State.ERROR, faultCpu.getState());
        assertTrue(faultCpu.getErrorMessage().contains("GPUCIRC"), faultCpu.getErrorMessage());
    }
}
