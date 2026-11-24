package games.jvrcruz.siliconcraft;

import games.jvrcruz.siliconcraft.compiler.Assembler;
import games.jvrcruz.siliconcraft.compiler.n1.NCompiler;
import games.jvrcruz.siliconcraft.compiler.n2.N2Compiler;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentationChapterExamplesTest {
    @Test
    void assemblyChapterSourcesAssemble() throws Exception {
        try (var files = Files.list(Path.of("docs/assembly/examples"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".asm")).sorted().toList()) {
                var result = Assembler.assemble(Files.readAllLines(file));
                assertTrue(result.success(), () -> file + ": " + result.errors());
            }
        }
    }

    @Test
    void n1ChapterSourcesAndProjectsCompile() throws Exception {
        Path directory = Path.of("docs/n-language/n1/examples");
        try (var files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) {
                if (Files.isDirectory(file)) {
                    var result = NCompiler.compileProject(projectSources(file, ".n"), "main.n");
                    assertTrue(result.success(), () -> file + ": " + result.errors());
                    assertAssembles(file, result.assembly());
                } else if (file.toString().endsWith(".n")) {
                    var result = NCompiler.compile(Files.readAllLines(file));
                    assertTrue(result.success(), () -> file + ": " + result.errors());
                    assertAssembles(file, result.assembly());
                }
            }
        }
    }

    @Test
    void n2ChapterSourcesAndProjectsCompile() throws Exception {
        Path directory = Path.of("docs/n-language/n2/examples");
        try (var files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) {
                if (Files.isDirectory(file)) {
                    var result = N2Compiler.compileProject(projectSources(file, ".n2"), "main.n2");
                    assertTrue(result.success(), () -> file + ": " + result.errors());
                    assertAssembles(file, result.assembly());
                } else if (file.toString().endsWith(".n2")) {
                    var result = N2Compiler.compile(Files.readString(file));
                    assertTrue(result.success(), () -> file + ": " + result.errors());
                    assertAssembles(file, result.assembly());
                }
            }
        }
    }

    private static Map<String, List<String>> projectSources(Path directory, String extension) throws Exception {
        Map<String, List<String>> sources = new LinkedHashMap<>();
        try (var files = Files.walk(directory)) {
            for (Path file : files.filter(path -> path.toString().endsWith(extension)).sorted().toList()) {
                sources.put(directory.relativize(file).toString().replace('\\', '/'), Files.readAllLines(file));
            }
        }
        return sources;
    }

    private static void assertAssembles(Path file, List<String> assembly) {
        var result = Assembler.assemble(assembly);
        assertTrue(result.success(), () -> file + ": " + result.errors());
    }
}
