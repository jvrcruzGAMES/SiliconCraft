package games.jvrcruz.siliconcraft;

import games.jvrcruz.siliconcraft.compiler.InstructionValidator;
import games.jvrcruz.siliconcraft.compiler.NCompiler;
import games.jvrcruz.siliconcraft.vm.VirtualCpu;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class CompilerAndValidatorTest {

    @Test
    public void testNCompilerSuccess() {
        List<String> nSource = List.of(
                "var a = 0",
                "var b = 1",
                "var count = 5",
                "while count > 0 {",
                "    println a",
                "    var next = a + b",
                "    a = b",
                "    b = next",
                "    count--",
                "}",
                "halt"
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "N compilation should succeed");
        assertFalse(result.assembly().isEmpty(), "Generated assembly should not be empty");
        assertTrue(result.requiredInstructions().contains("OUT"));
        assertTrue(result.requiredInstructions().contains("HLT"));
        assertTrue(result.requiredInstructions().contains("MOV"));
    }

    @Test
    public void testInstructionValidatorMissingOpcodes() {
        List<String> assembly = List.of(
                "MOV A, 0",
                "INC A",
                "OUT A",
                "CMP A, 10",
                "JNZ loop",
                "HLT"
        );

        Set<String> required = InstructionValidator.getRequiredInstructions(assembly);
        assertTrue(required.contains("MOV"));
        assertTrue(required.contains("INC"));
        assertTrue(required.contains("OUT"));
        assertTrue(required.contains("CMP"));
        assertTrue(required.contains("JNZ"));
        assertTrue(required.contains("HLT"));

        // Test with architecture missing OUT and CMP
        List<String> archSupported = List.of("MOV", "INC", "JNZ", "HLT");
        List<String> missing = InstructionValidator.getMissingInstructions(required, archSupported);

        assertEquals(2, missing.size());
        assertTrue(missing.contains("OUT"));
        assertTrue(missing.contains("CMP"));

        // Test with full architecture
        List<String> fullArch = List.of("MOV", "INC", "OUT", "OUTC", "CMP", "JNZ", "HLT", "ADD");
        List<String> noneMissing = InstructionValidator.getMissingInstructions(required, fullArch);
        assertTrue(noneMissing.isEmpty());
    }

    @Test
    public void testNCompilerSyntaxError() {
        List<String> badSource = List.of(
                "var x = 5",
                "while x > {",
                "    println x"
        );

        NCompiler.CompileResult result = NCompiler.compile(badSource);
        assertFalse(result.success(), "Invalid syntax should fail compilation");
        assertFalse(result.errors().isEmpty());
    }

    @Test
    public void testExplicitTypeDeclarationsRejected() {
        List<String> intSource = List.of("int x = 5");
        NCompiler.CompileResult r1 = NCompiler.compile(intSource);
        assertFalse(r1.success(), "Explicit 'int' should be rejected");
        assertTrue(r1.errors().get(0).contains("Explicit type declaration"));

        List<String> charSource = List.of("char c = 'a'");
        NCompiler.CompileResult r2 = NCompiler.compile(charSource);
        assertFalse(r2.success(), "Explicit 'char' should be rejected");

        List<String> charArrSource = List.of("char[] s = \"hello\"");
        NCompiler.CompileResult r3 = NCompiler.compile(charArrSource);
        assertFalse(r3.success(), "Explicit 'char[]' should be rejected");
    }

    @Test
    public void testConstantImmutability() {
        List<String> constSource = List.of(
                "const MAX = 100",
                "MAX = 200"
        );
        NCompiler.CompileResult result = NCompiler.compile(constSource);
        assertFalse(result.success(), "Reassigning to constant should fail");
        assertTrue(result.errors().get(0).contains("Cannot assign to constant"));
    }

    @Test
    public void testPrintCharRemoved() {
        List<String> source = List.of("print_char 'x'");
        NCompiler.CompileResult result = NCompiler.compile(source);
        assertFalse(result.success(), "print_char should be removed/rejected");
        assertTrue(result.errors().get(0).contains("'print_char' has been removed"));
    }

    @Test
    public void testBooleanAndPrintlnExecution() {
        List<String> nSource = List.of(
                "var isOnline = true",
                "const hasErrors = false",
                "println isOnline",
                "println hasErrors",
                "if isOnline {",
                "    println \"System Ready\"",
                "}",
                "halt"
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "Boolean code should compile: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "CMP", "JZ", "JNZ", "JMP", "OUT", "OUTC", "HLT");
        cpu.configure(isa, 256, result.assembly(), false, 0);
        cpu.powerOn();
        cpu.tick(200);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        List<String> output = cpu.getTerminalOutput();
        assertTrue(output.contains("true"), "Output should contain 'true': " + output);
        assertTrue(output.contains("false"), "Output should contain 'false': " + output);
        assertTrue(output.contains("System Ready"), "Output should contain 'System Ready': " + output);
    }

    @Test
    public void testLuaStyleTablesAndExecution() {
        List<String> nSource = List.of(
                "var user = { name = \"Steve\", age = 30, alive = true }",
                "var nums = { 10, 20, 30 }",
                "println user.name",
                "println user.age",
                "println user.alive",
                "println nums[0]",
                "user.age = 31",
                "nums[0] = 99",
                "println user.age",
                "println nums[0]",
                "halt"
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "Lua-style table code should compile: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "ADD", "CMP", "JZ", "JNZ", "JMP", "INC", "OUT", "OUTC", "HLT");
        cpu.configure(isa, 256, result.assembly(), false, 0);
        cpu.powerOn();
        cpu.tick(300);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        List<String> output = cpu.getTerminalOutput();
        assertTrue(output.contains("Steve"), "Output should contain 'Steve': " + output);
        assertTrue(output.contains("30"), "Output should contain '30': " + output);
        assertTrue(output.contains("true"), "Output should contain 'true': " + output);
        assertTrue(output.contains("10"), "Output should contain '10': " + output);
        assertTrue(output.contains("31"), "Output should contain modified age '31': " + output);
        assertTrue(output.contains("99"), "Output should contain modified element '99': " + output);
    }

    @Test
    public void testCharLiteralEscapes() {
        assertEquals(10, VirtualCpu.parseCharLiteral("\\n"));
        assertEquals(9, VirtualCpu.parseCharLiteral("\\t"));
        assertEquals(0, VirtualCpu.parseCharLiteral("\\0"));
        assertEquals(65, VirtualCpu.parseCharLiteral("A"));
        assertEquals(0x2605, VirtualCpu.parseCharLiteral("★"));
    }

    @Test
    public void testPrintOnCurrentLine() {
        List<String> nSource = List.of(
                "print \"Hello \"",
                "println \"World\"",
                "var x = 42",
                "print \"Value: \"",
                "print x",
                "println \"!\"",
                "var flag = true",
                "print \"Flag is \"",
                "println flag",
                "halt"
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "Compilation should succeed: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "CMP", "JZ", "JNZ", "JMP", "OUT", "OUTC", "HLT");
        cpu.configure(isa, 256, result.assembly(), false, 0);
        cpu.powerOn();
        cpu.tick(300);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        List<String> output = cpu.getTerminalOutput();

        assertTrue(output.contains("Hello World"), "Output should contain 'Hello World' on one line: " + output);
        assertTrue(output.contains("Value: 42!"), "Output should contain 'Value: 42!' on one line: " + output);
        assertTrue(output.contains("Flag is true"), "Output should contain 'Flag is true' on one line: " + output);
    }

    @Test
    public void testPrintWithTablesAndStrings() {
        List<String> nSource = List.of(
                "var user = { name = \"Alex\", score = 100 }",
                "print \"Player: \"",
                "print user.name",
                "print \" | Score: \"",
                "println user.score",
                "halt"
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "Compilation should succeed: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "ADD", "CMP", "JZ", "JNZ", "JMP", "INC", "OUT", "OUTC", "HLT");
        cpu.configure(isa, 256, result.assembly(), false, 0);
        cpu.powerOn();
        cpu.tick(300);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        List<String> output = cpu.getTerminalOutput();

        assertTrue(output.contains("Player: Alex | Score: 100"),
                "Output should contain 'Player: Alex | Score: 100' on a single line: " + output);
    }

    @Test
    public void testUserLoopPointsSnippet() {
        List<String> nSource = List.of(
                "println \"JTech Computers\"",
                "println \"Booting\"",
                "println \"\"",
                "var points = 0",
                "while points < 5 {",
                "    println points",
                "    points++",
                "}",
                "halt"
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "User snippet should compile: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "CMP", "JGE", "JL", "JMP", "INC", "OUT", "OUTC", "HLT");
        cpu.configure(isa, 256, result.assembly(), false, 0);
        cpu.powerOn();
        cpu.tick(200);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        List<String> output = cpu.getTerminalOutput();
        assertTrue(output.contains("JTech Computers"));
        assertTrue(output.contains("Booting"));
        assertTrue(output.contains("0"));
        assertTrue(output.contains("1"));
        assertTrue(output.contains("4"));
        assertFalse(output.contains("5")); // points < 5 loop terminates before 5
    }

    @Test
    public void testSleepAndTimeAndCls() {
        List<String> nSource = List.of(
                "println \"Old Line\"",
                "cls()",
                "var t = time.now()",
                "sleep(1)",
                "println \"Awake\"",
                "halt"
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "Sleep and time code should compile: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        cpu.setTimeSupplier(() -> 12345L);
        Set<String> isa = Set.of("MOV", "OUT", "OUTC", "CLS", "TIME", "SLEEP", "HLT");
        cpu.configure(isa, 256, result.assembly(), false, 0);
        cpu.powerOn();

        // 1 tick = 10 CPU cycles. Program reaches sleep(1) which sets sleepTicksRemaining = 20
        cpu.tick(10);
        assertEquals(20, cpu.getSleepTicksRemaining(), "Should have 20 sleep ticks remaining for 1 second");

        // Tick 20 times to exhaust sleep
        for (int i = 0; i < 20; i++) {
            cpu.tick(10);
        }
        assertEquals(0, cpu.getSleepTicksRemaining());

        // Now tick to finish program
        cpu.tick(10);
        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        List<String> output = cpu.getTerminalOutput();
        assertFalse(output.contains("Old Line"), "CLS should have cleared 'Old Line'");
        assertTrue(output.contains("Awake"));
    }

    @Test
    public void testFunctionsAndMath() {
        List<String> nSource = List.of(
                "fn calc() {",
                "    var a = 20",
                "    var b = 4",
                "    var mul = a * b",
                "    var div = a / b",
                "    var mod = a % 6",
                "    return mul + div + mod",
                "}",
                "var ans = calc()",
                "println ans",
                "halt"
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "Functions and math should compile: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "ADD", "MUL", "DIV", "MOD", "CALL", "RET", "JMP", "OUT", "OUTC", "HLT");
        cpu.configure(isa, 256, result.assembly(), false, 0);
        cpu.powerOn();
        cpu.tick(200);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        List<String> output = cpu.getTerminalOutput();
        // 20 * 4 = 80; 20 / 4 = 5; 20 % 6 = 2; sum = 80 + 5 + 2 = 87
        assertTrue(output.contains("87"), "Output should contain 87: " + output);
    }

    @Test
    public void testImportRejectedInStandardCompiler() {
        List<String> source = List.of(
                "import \"utils.n\"",
                "println \"Hello\""
        );
        NCompiler.CompileResult result = NCompiler.compile(source);
        assertFalse(result.success());
        assertTrue(result.errors().get(0).contains("Imports are not supported in the default Code Compiler"));
    }

    @Test
    public void testProjectCompilerBasicImport() {
        java.util.Map<String, List<String>> files = new java.util.HashMap<>();
        files.put("main.n", List.of(
                "import \"utils.n\"",
                "var res = doubleVal()",
                "println res"
        ));
        files.put("utils.n", List.of(
                "var factor = 2",
                "fn doubleVal() {",
                "    var x = 21",
                "    return x * factor",
                "}"
        ));

        NCompiler.CompileResult result = NCompiler.compileProject(files, "main.n");
        assertTrue(result.success(), "Project compile should succeed: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "MUL", "CALL", "RET", "JMP", "OUT", "OUTC", "HLT");
        cpu.configure(isa, 256, result.assembly(), false, 0);
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertTrue(cpu.getTerminalOutput().contains("42"), "Terminal output should contain 42: " + cpu.getTerminalOutput());
    }

    @Test
    public void testProjectCompilerSubfoldersAndRelativeImports() {
        java.util.Map<String, List<String>> files = new java.util.HashMap<>();
        files.put("main.n", List.of(
                "import \"math/vec.n\"",
                "import \"core/engine.n\"",
                "runEngine()"
        ));
        files.put("math/vec.n", List.of(
                "const DIM = 3",
                "fn getDim() {",
                "    return DIM",
                "}"
        ));
        files.put("core/engine.n", List.of(
                "import \"../math/vec.n\"",
                "fn runEngine() {",
                "    var d = getDim()",
                "    println d",
                "}"
        ));

        NCompiler.CompileResult result = NCompiler.compileProject(files, "main.n");
        assertTrue(result.success(), "Subfolder project compile should succeed: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "CALL", "RET", "JMP", "OUT", "OUTC", "HLT");
        cpu.configure(isa, 256, result.assembly(), false, 0);
        cpu.powerOn();
        cpu.tick(100);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertTrue(cpu.getTerminalOutput().contains("3"), "Terminal output should contain 3: " + cpu.getTerminalOutput());
    }

    @Test
    public void testProjectCompilerCircularImportDetected() {
        java.util.Map<String, List<String>> files = new java.util.HashMap<>();
        files.put("main.n", List.of(
                "import \"a.n\""
        ));
        files.put("a.n", List.of(
                "import \"b.n\""
        ));
        files.put("b.n", List.of(
                "import \"a.n\""
        ));

        NCompiler.CompileResult result = NCompiler.compileProject(files, "main.n");
        assertFalse(result.success(), "Circular import must fail");
        assertTrue(result.errors().get(0).contains("Circular import detected"), "Error should mention circular import: " + result.errors());
    }

    @Test
    public void testProjectCompilerMissingEntrypoint() {
        java.util.Map<String, List<String>> files = new java.util.HashMap<>();
        files.put("helper.n", List.of("println \"no main\""));

        NCompiler.CompileResult result = NCompiler.compileProject(files, null);
        assertFalse(result.success());
        assertTrue(result.errors().get(0).contains("main.n"));
    }

    @Test
    public void testZipNameExtraction() {
        assertEquals("MyProject.asm", NCompiler.getAsmNameFromZip("MyProject.zip"));
        assertEquals("space_invaders.asm", NCompiler.getAsmNameFromZip("/path/to/space_invaders.ZIP"));
        assertEquals("game.asm", NCompiler.getAsmNameFromZip("game"));
    }

    @Test
    public void testProjectZipUnpackAndCompile() throws Exception {
        // Create an in-memory zip containing:
        // - main.n
        // - math/operations.n
        // - io/display.n (which imports math/operations.n via "../math/operations.n")
        java.io.ByteArrayOutputStream zipBaos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(zipBaos, java.nio.charset.StandardCharsets.UTF_8)) {
            // entry: math/operations.n
            zos.putNextEntry(new java.util.zip.ZipEntry("math/operations.n"));
            zos.write("var baseVal = 32\nfn addTen() {\n    return baseVal + 10\n}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();

            // entry: io/display.n
            zos.putNextEntry(new java.util.zip.ZipEntry("io/display.n"));
            zos.write("import \"../math/operations.n\"\nfn showResult() {\n    var x = addTen()\n    print \"Result: \"\n    println x\n}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();

            // entry: main.n
            zos.putNextEntry(new java.util.zip.ZipEntry("main.n"));
            zos.write("import \"io/display.n\"\nshowResult()\nhalt\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        // Unpack zip using exact logic from ProjectCompilerScreen
        java.util.Map<String, List<String>> extracted = new java.util.LinkedHashMap<>();
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zipBaos.toByteArray()), java.nio.charset.StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[4096];
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().toLowerCase().endsWith(".n")) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }
                    String text = baos.toString(java.nio.charset.StandardCharsets.UTF_8);
                    List<String> rawLines = java.util.Arrays.asList(text.split("\\r?\\n"));
                    List<String> cleaned = new java.util.ArrayList<>();
                    for (String line : rawLines) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty()) {
                            cleaned.add(trimmed);
                        }
                    }
                    String normPath = entry.getName().replace('\\', '/');
                    while (normPath.startsWith("/")) {
                        normPath = normPath.substring(1);
                    }
                    extracted.put(normPath, cleaned);
                }
                zis.closeEntry();
            }
        }

        assertEquals(3, extracted.size(), "Should extract 3 .n files");
        assertTrue(extracted.containsKey("main.n"));
        assertTrue(extracted.containsKey("math/operations.n"));
        assertTrue(extracted.containsKey("io/display.n"));

        // Compile project
        NCompiler.CompileResult result = NCompiler.compileProject(extracted, null);
        assertTrue(result.success(), "Compile should succeed: " + result.errors());

        // Validate asm filename
        String asmName = NCompiler.getAsmNameFromZip("MyAwesomeGame.zip");
        assertEquals("MyAwesomeGame.asm", asmName);

        // Run on VirtualCpu
        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of("MOV", "ADD", "CALL", "RET", "JMP", "OUT", "OUTC", "HLT");
        cpu.configure(isa, 256, result.assembly(), false, 0);
        cpu.powerOn();
        cpu.tick(150);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        List<String> out = cpu.getTerminalOutput();
        assertTrue(out.contains("Result: 42"), "Output should contain 'Result: 42': " + out);
    }

    @Test
    public void testProjectCompilerFolderLoading() throws Exception {
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("siliconcraft_test_proj_dir");
        try {
            java.nio.file.Path mathDir = tempDir.resolve("math");
            java.nio.file.Path ioDir = tempDir.resolve("io");
            java.nio.file.Files.createDirectories(mathDir);
            java.nio.file.Files.createDirectories(ioDir);

            java.nio.file.Files.writeString(mathDir.resolve("operations.n"),
                    "fn addTen() -> int {\n    return 32 + 10\n}\n", java.nio.charset.StandardCharsets.UTF_8);

            java.nio.file.Files.writeString(ioDir.resolve("display.n"),
                    "import \"../math/operations.n\"\nfn showResult() {\n    var x = addTen()\n    print \"Result: \"\n    println x\n}\n", java.nio.charset.StandardCharsets.UTF_8);

            java.nio.file.Files.writeString(tempDir.resolve("main.n"),
                    "import \"io/display.n\"\nshowResult()\nhalt\n", java.nio.charset.StandardCharsets.UTF_8);

            // Load from folder using NCompiler.loadProjectFromFolder
            java.util.Map<String, List<String>> extracted = NCompiler.loadProjectFromFolder(tempDir.toFile());

            assertEquals(3, extracted.size(), "Should load 3 .n files from folder");
            assertTrue(extracted.containsKey("main.n"), "Root main.n must be present");
            assertTrue(extracted.containsKey("math/operations.n"), "math/operations.n must be present");
            assertTrue(extracted.containsKey("io/display.n"), "io/display.n must be present");

            // Compile loaded folder project
            NCompiler.CompileResult result = NCompiler.compileProject(extracted, null);
            assertTrue(result.success(), "Project compile from folder should succeed: " + result.errors());

            // Run on VirtualCpu
            VirtualCpu cpu = new VirtualCpu();
            Set<String> isa = Set.of("MOV", "ADD", "CALL", "RET", "JMP", "OUT", "OUTC", "HLT");
            cpu.configure(isa, 256, result.assembly(), false, 0);
            cpu.powerOn();
            cpu.tick(150);

            assertEquals(VirtualCpu.State.HALTED, cpu.getState());
            List<String> out = cpu.getTerminalOutput();
            assertTrue(out.contains("Result: 42"), "Output should contain 'Result: 42': " + out);
        } finally {
            // Recursively delete temp directory
            try (var s = java.nio.file.Files.walk(tempDir)) {
                s.sorted(java.util.Comparator.reverseOrder())
                 .map(java.nio.file.Path::toFile)
                 .forEach(java.io.File::delete);
            }
        }
    }

    @Test
    public void testNStorageHelpers() {
        List<String> nSource = List.of(
                "var sz = storage.size()",
                "storage.write(0, 42)",
                "var byteVal = storage.read(0)",
                "storage.write_int(4, 99999)",
                "var val = storage.read_int(4)",
                "storage.write_string(16, \"MyFS\")",
                "var name = storage.read_string(16, 4)",
                "print \"Capacity: \"",
                "println sz",
                "print \"Byte: \"",
                "println byteVal",
                "print \"Int: \"",
                "println val",
                "print \"FS: \"",
                "println name",
                "halt"
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "N code with storage helpers should compile: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of(
                "MOV", "INC", "SHL", "SHR", "OR", "CMP", "JZ", "JNZ", "JMP",
                "OUT", "OUTC", "SSIZE", "SREAD", "SWRITE", "SBLKR", "SBLKW", "HLT"
        );
        // Configure with 512KB storage
        cpu.configure(isa, 512, result.assembly(), false, 512);
        cpu.powerOn();
        cpu.tick(300);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        List<String> output = cpu.getTerminalOutput();

        assertTrue(output.contains("Capacity: 524288"), "Output should contain storage capacity: " + output);
        assertTrue(output.contains("Byte: 42"), "Output should contain byte 42: " + output);
        assertTrue(output.contains("Int: 99999"), "Output should contain int 99999: " + output);
        assertTrue(output.contains("FS: MyFS"), "Output should contain 'FS: MyFS': " + output);
    }

    @Test
    public void testNFramebufferAndAudioHelpers() {
        List<String> nSource = List.of(
                "display.mode(1)",
                "var w = display.width()",
                "var h = display.height()",
                "display.clear(0x000000)",
                "display.rect(10, 10, 20, 20, 0xFF0000)",
                "display.line(0, 0, 50, 50, 0x00FF00)",
                "display.set(5, 5, 0x0000FF)",
                "var pix = display.get(5, 5)",
                "display.swap()",
                "audio.sample(128)",
                "audio.sample(255)",
                "var aSize = audio.status()",
                "audio.play()",
                "audio.tone(440, 100)",
                "print \"W: \"",
                "println w",
                "print \"H: \"",
                "println h",
                "print \"Pix: \"",
                "println pix",
                "print \"ASize: \"",
                "println aSize",
                "halt"
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "N code with FB and Audio helpers should compile: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of(
                "MOV", "OUT", "OUTC", "FBMODE", "FBSET", "FBGET", "FBCLR", "FBRECT", "FBLINE", "FBLIT", "FBSWAP",
                "FBWIDTH", "FBHEIGHT", "APUSH", "ABUFW", "ACLR", "ASTAT", "ATONE", "APLAY", "HLT"
        );
        cpu.configure(isa, 512, result.assembly(), false, 0);
        cpu.powerOn();
        cpu.tick(200);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        assertEquals(1, cpu.getDisplayMode());
        int[] fb = cpu.getFramebuffer();
        assertEquals(0xFF0000FF, fb[5 * VirtualCpu.FB_WIDTH + 5]);
        assertEquals(0xFF00FF00, fb[15 * VirtualCpu.FB_WIDTH + 15]); // On diagonal line
        assertEquals(0xFFFF0000, fb[12 * VirtualCpu.FB_WIDTH + 20]); // Inside rectangle

        List<String> output = cpu.getTerminalOutput();
        assertTrue(output.contains("W: 240"), "Output should contain width 240: " + output);
        assertTrue(output.contains("H: 135"), "Output should contain height 135: " + output);
        assertTrue(output.contains("Pix: -16776961"), "Output should contain 0xFF0000FF pixel value: " + output);
        assertTrue(output.contains("ASize: 2"), "Output should contain audio size 2: " + output);
    }

    @Test
    public void testNMultiDriveOperations() {
        List<String> nSource = List.of(
                "var cnt = storage.count()",
                "storage.select(1)",
                "var cur = storage.selected()",
                "storage[1].write(5, 77)",
                "storage[2].write(5, 88)",
                "var v1 = storage[1].read(5)",
                "var v2 = storage[2].read(5)",
                "storage.write(1, 10, 111)",
                "var v3 = storage.read(1, 10)",
                "print \"Count: \"",
                "println cnt",
                "print \"Selected: \"",
                "println cur",
                "print \"V1: \"",
                "println v1",
                "print \"V2: \"",
                "println v2",
                "print \"V3: \"",
                "println v3",
                "halt"
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "N multi-drive operations should compile: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of(
                "MOV", "OUT", "OUTC", "SSIZE", "SREAD", "SWRITE", "SBLKR", "SBLKW",
                "SCOUNT", "SSEL", "SDRV", "HLT"
        );
        cpu.configure(isa, 512, result.assembly(), false, 0);

        byte[] drv1 = new byte[64];
        byte[] drv2 = new byte[64];
        cpu.setStorageDrive(1, drv1);
        cpu.setStorageDrive(2, drv2);

        cpu.powerOn();
        cpu.tick(300);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        List<String> output = cpu.getTerminalOutput();

        assertTrue(output.contains("Count: 2"), "Output should contain 'Count: 2': " + output);
        assertTrue(output.contains("Selected: 1"), "Output should contain 'Selected: 1': " + output);
        assertTrue(output.contains("V1: 77"), "Output should contain 'V1: 77': " + output);
        assertTrue(output.contains("V2: 88"), "Output should contain 'V2: 88': " + output);
        assertTrue(output.contains("V3: 111"), "Output should contain 'V3: 111': " + output);
        assertEquals((byte) 77, drv1[5]);
        assertEquals((byte) 88, drv2[5]);
        assertEquals((byte) 111, drv1[10]);
    }

    @Test
    public void testHexadecimalAndPointers() {
        // Test isNumeric and parseNumber helpers
        assertTrue(NCompiler.isNumeric("0x10"));
        assertTrue(NCompiler.isNumeric("0X10"));
        assertTrue(NCompiler.isNumeric("-0x10"));
        assertTrue(NCompiler.isNumeric("0xFF"));
        assertTrue(NCompiler.isNumeric("0xFFFFFFFF"));
        assertTrue(NCompiler.isNumeric("255"));
        assertTrue(NCompiler.isNumeric("-255"));
        assertFalse(NCompiler.isNumeric("0xG1"));
        assertFalse(NCompiler.isNumeric("hello"));

        assertEquals(16, NCompiler.parseNumber("0x10"));
        assertEquals(16, NCompiler.parseNumber("0X10"));
        assertEquals(-16, NCompiler.parseNumber("-0x10"));
        assertEquals(255, NCompiler.parseNumber("0xFF"));
        assertEquals(-1, NCompiler.parseNumber("0xFFFFFFFF"));
        assertEquals(Integer.MIN_VALUE, NCompiler.parseNumber("0x80000000"));
        assertEquals(255, NCompiler.parseNumber("255"));
        assertEquals(-255, NCompiler.parseNumber("-255"));

        List<String> nSource = List.of(
                "var numA = 0x10",
                "var numB = 0x20",
                "var sum = numA + numB",
                "print \"Sum: \"",
                "println sum",
                // Bitwise expressions with parentheses and precedence
                "var bitwise = (0x0F << 4) | 0x0A",
                "print \"Bitwise: \"",
                "println bitwise",
                "var anded = bitwise & 0xF0",
                "print \"Anded: \"",
                "println anded",
                // Bitwise compound assignments
                "var comp = 0x0F",
                "comp <<= 4",
                "comp |= 0x05",
                "print \"Comp: \"",
                "println comp",
                // Pointer operations
                "var target = 0x42",
                "var ptr = &target",
                "*ptr = 0x77",
                "print \"Target: \"",
                "println target",
                "var fromPtr = *ptr",
                "print \"FromPtr: \"",
                "println fromPtr",
                // Direct RAM memory
                "*0x50 = 0x99",
                "var directRam = *0x50",
                "print \"DirectRam: \"",
                "println directRam",
                "mem[0x60] = 0xAA",
                "var memBracket = mem[0x60]",
                "print \"MemBracket: \"",
                "println memBracket",
                "mem.write(0x70, 0xBB)",
                "var memRead = mem.read(0x70)",
                "print \"MemRead: \"",
                "println memRead",
                "poke(0x80, 0xCC)",
                "var peekVal = peek(0x80)",
                "print \"PeekVal: \"",
                "println peekVal",
                // Hex output
                "print \"Hex: \"",
                "println_hex numA",
                "print_hex 0xAA",
                "println \"\"",
                "halt"
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "N compilation should succeed with hex & pointers: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of(
                "MOV", "ADD", "SUB", "MUL", "DIV", "MOD", "INC", "DEC", "CMP",
                "AND", "OR", "XOR", "NOT", "SHL", "SHR", "OUT", "OUTH", "OUTC", "HLT"
        );
        cpu.configure(isa, 512, result.assembly(), false, 0);
        cpu.powerOn();
        cpu.tick(300);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        List<String> output = cpu.getTerminalOutput();

        assertTrue(output.contains("Sum: 48"), "0x10 + 0x20 should be 48: " + output);
        assertTrue(output.contains("Bitwise: 250"), "(0x0F << 4) | 0x0A should be 250: " + output);
        assertTrue(output.contains("Anded: 240"), "250 & 0xF0 should be 240: " + output);
        assertTrue(output.contains("Comp: 245"), "(0x0F << 4) | 0x05 should be 245: " + output);
        assertTrue(output.contains("Target: 119"), "target should be updated via pointer to 0x77 (119): " + output);
        assertTrue(output.contains("FromPtr: 119"), "fromPtr should read 119: " + output);
        assertTrue(output.contains("DirectRam: 153"), "*0x50 should read 0x99 (153): " + output);
        assertTrue(output.contains("MemBracket: 170"), "mem[0x60] should read 0xAA (170): " + output);
        assertTrue(output.contains("MemRead: 187"), "mem.read(0x70) should read 0xBB (187): " + output);
        assertTrue(output.contains("PeekVal: 204"), "peek(0x80) should read 0xCC (204): " + output);
        assertTrue(output.contains("Hex: 0x10"), "println_hex numA should output 'Hex: 0x10': " + output);
        assertTrue(output.contains("0xAA"), "print_hex 0xAA should output '0xAA': " + output);
    }

    @Test
    public void testHexadecimalStorageAndTables() {
        List<String> nSource = List.of(
                // Hex table indexing and values
                "var t = { [0x01] = 0xAA, [0x02] = 0xBB }",
                "var v1 = t[0x01]",
                "t[0x02] = 0xCC",
                "var v2 = t[0x02]",
                "print \"V1: \"",
                "println v1",
                "print \"V2: \"",
                "println v2",
                // Multi-drive storage with hex drives and addresses
                "storage.select(0x01)",
                "storage.write(0x01, 0x10, 0xDE)",
                "var sVal = storage.read(0x01, 0x10)",
                "print \"StorageVal: \"",
                "println sVal",
                "storage[0x02].write(0x20, 0xEA)",
                "var sVal2 = storage[0x02].read(0x20)",
                "print \"StorageVal2: \"",
                "println sVal2",
                "halt"
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "N compilation should succeed with hex storage and tables: " + result.errors());

        VirtualCpu cpu = new VirtualCpu();
        Set<String> isa = Set.of(
                "MOV", "ADD", "SUB", "OUT", "OUTC", "SREAD", "SWRITE", "SSEL", "HLT"
        );
        cpu.configure(isa, 512, result.assembly(), false, 0);

        byte[] drv1 = new byte[64];
        byte[] drv2 = new byte[64];
        cpu.setStorageDrive(1, drv1);
        cpu.setStorageDrive(2, drv2);

        cpu.powerOn();
        cpu.tick(300);

        assertEquals(VirtualCpu.State.HALTED, cpu.getState());
        List<String> output = cpu.getTerminalOutput();

        assertTrue(output.contains("V1: 170"), "t[0x01] should be 170 (0xAA): " + output);
        assertTrue(output.contains("V2: 204"), "t[0x02] should be 204 (0xCC): " + output);
        assertTrue(output.contains("StorageVal: 222"), "storage.read(0x01, 0x10) should be 222 (0xDE): " + output);
        assertTrue(output.contains("StorageVal2: 234"), "storage[0x02].read(0x20) should be 234 (0xEA): " + output);
        assertEquals((byte) 0xDE, drv1[0x10]);
        assertEquals((byte) 0xEA, drv2[0x20]);
    }

    @Test
    public void testNLanguageInterruptHelpers() {
        List<String> nSource = List.of(
                "on_key(handle_key)",
                "on_mouse_move(handle_mouse)",
                "on_mouse_click(handle_click)",
                "catch(0, handle_div_zero)",
                "catch(DIV_ZERO, handle_div_zero)",
                "CLI",
                "STI",
                "INT 10",
                "halt",
                "handle_key:",
                "IRET",
                "handle_mouse:",
                "IRET",
                "handle_click:",
                "IRET",
                "handle_div_zero:",
                "IRET"
        );

        NCompiler.CompileResult result = NCompiler.compile(nSource);
        assertTrue(result.success(), "Interrupt helper code should compile successfully: " + result.errors());
        assertTrue(result.assembly().contains("IVEC 10, handle_key"));
        assertTrue(result.assembly().contains("IVEC 11, handle_mouse"));
        assertTrue(result.assembly().contains("IVEC 12, handle_click"));
        assertTrue(result.assembly().contains("IVEC 0, handle_div_zero"));
        assertTrue(result.assembly().contains("IVEC DIV_ZERO, handle_div_zero"));
        assertTrue(result.assembly().contains("CLI"));
        assertTrue(result.assembly().contains("STI"));
        assertTrue(result.assembly().contains("INT 10"));

        Set<String> req = result.requiredInstructions();
        assertTrue(req.contains("IVEC"));
        assertTrue(req.contains("IRET"));
        assertTrue(req.contains("CLI"));
        assertTrue(req.contains("STI"));
        assertTrue(req.contains("INT"));
    }
}

