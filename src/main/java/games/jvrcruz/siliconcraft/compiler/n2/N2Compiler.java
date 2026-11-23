package games.jvrcruz.siliconcraft.compiler.n2;

import games.jvrcruz.siliconcraft.compiler.InstructionValidator;

import java.util.*;

public class N2Compiler {

    public record CompileResult(
            boolean success,
            List<String> assembly,
            List<String> errors,
            Set<String> requiredInstructions
    ) {}

    public static CompileResult compile(List<String> sourceLines) {
        if (sourceLines == null || sourceLines.isEmpty()) {
            return new CompileResult(false, List.of(), List.of("Source code is empty!"), Set.of());
        }
        return compile(String.join("\n", sourceLines), "main.n2");
    }

    public static CompileResult compile(String source) {
        return compile(source, "main.n2");
    }

    public static CompileResult compile(String source, String fileName) {
        if (source == null || source.trim().isEmpty()) {
            return new CompileResult(false, List.of(), List.of("Source code is empty!"), Set.of());
        }

        try {
            N2Lexer lexer = new N2Lexer(source, fileName);
            List<N2Token> tokens = lexer.tokenize();

            N2Parser parser = new N2Parser(tokens, fileName);
            N2AST.ProgramNode program = parser.parse();

            if (program.imports.stream().anyMatch(imp -> !imp.isLibrary && imp.alias != null)) {
                return new CompileResult(false, List.of(), List.of("Module aliases require project compilation"), Set.of());
            }

            if (program.imports.stream().anyMatch(imp -> imp.alias != null)) {
                var linked = new N2AST.ProgramNode(1, 1);
                program.imports.stream().filter(imp -> imp.alias == null).forEach(linked.imports::add);
                var errors = new ArrayList<String>();
                N2ModuleAliases.merge(Map.of(fileName == null ? "main.n2" : fileName, program), Map.of(),
                        Set.of(fileName == null ? "main.n2" : fileName), linked, errors);
                if (!errors.isEmpty()) return new CompileResult(false, List.of(), errors, Set.of());
                program = linked;
            }

            N2CodeGenerator codegen = new N2CodeGenerator(program, fileName);
            List<String> assembly = codegen.generate();
            List<String> errors = codegen.getErrors();

            if (!errors.isEmpty()) {
                return new CompileResult(false, List.of(), errors, Set.of());
            }

            Set<String> req = new LinkedHashSet<>(codegen.getRequiredInstructions());
            req.addAll(InstructionValidator.getRequiredInstructions(assembly));

            return new CompileResult(true, assembly, List.of(), req);
        } catch (N2CompileException e) {
            return new CompileResult(false, List.of(), List.of(e.getMessage()), Set.of());
        } catch (Exception e) {
            return new CompileResult(false, List.of(), List.of("Compiler internal error: " + e.getMessage()), Set.of());
        }
    }

    public static CompileResult compileProject(Map<String, List<String>> files, String entrypoint) {
        if (files == null || files.isEmpty()) {
            return new CompileResult(false, List.of(), List.of("Project contains no files!"), Set.of());
        }

        Map<String, List<String>> normalizedMap = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : files.entrySet()) {
            normalizedMap.put(N2ProjectResolver.normalizePath(entry.getKey()), entry.getValue());
        }

        String selectedEntrypoint = null;
        if (entrypoint != null && !entrypoint.trim().isEmpty()) {
            String norm = N2ProjectResolver.normalizePath(entrypoint.trim());
            if (normalizedMap.containsKey(norm)) {
                selectedEntrypoint = norm;
            }
        }

        if (selectedEntrypoint == null) {
            String[] commonEntrypoints = {
                    "main.n2", "main.n", "main.asm", "main.s", "main.S",
                    "entry.n2", "entry.n", "entry.asm", "entry.s", "entry.S",
                    "start.n2", "start.n", "start.asm", "start.s", "start.S",
                    "index.n2", "index.n", "index.asm", "index.s", "index.S"
            };
            for (String cand : commonEntrypoints) {
                if (normalizedMap.containsKey(cand)) {
                    selectedEntrypoint = cand;
                    break;
                }
            }
        }

        if (selectedEntrypoint == null) {
            for (String path : normalizedMap.keySet()) {
                String lower = path.toLowerCase(Locale.ROOT);
                if (lower.endsWith("/main.n2") || lower.endsWith("/main.n") || lower.endsWith("/main.asm") || lower.endsWith("/main.s") ||
                    lower.endsWith("/entry.n2") || lower.endsWith("/entry.n") || lower.endsWith("/entry.asm") || lower.endsWith("/entry.s") ||
                    lower.endsWith("/start.n2") || lower.endsWith("/start.n") || lower.endsWith("/start.asm") || lower.endsWith("/start.s")) {
                    selectedEntrypoint = path;
                    break;
                }
            }
        }

        if (selectedEntrypoint == null) {
            return new CompileResult(false, List.of(), List.of("Project must contain a 'main.n2' (or 'main.n' / 'main.asm') entrypoint!"), Set.of());
        }

        List<String> errors = new ArrayList<>();
        N2AST.ProgramNode mergedProgram = N2ProjectResolver.resolveAndMergeProject(selectedEntrypoint, normalizedMap, errors);

        if (!errors.isEmpty()) {
            return new CompileResult(false, List.of(), errors, Set.of());
        }

        try {
            N2CodeGenerator codegen = new N2CodeGenerator(mergedProgram, selectedEntrypoint);
            List<String> assembly = codegen.generate();
            errors.addAll(codegen.getErrors());

            if (!errors.isEmpty()) {
                return new CompileResult(false, List.of(), errors, Set.of());
            }

            Set<String> req = new LinkedHashSet<>(codegen.getRequiredInstructions());
            req.addAll(InstructionValidator.getRequiredInstructions(assembly));

            return new CompileResult(true, assembly, List.of(), req);
        } catch (N2CompileException e) {
            return new CompileResult(false, List.of(), List.of(e.getMessage()), Set.of());
        } catch (Exception e) {
            return new CompileResult(false, List.of(), List.of("Compiler error: " + e.getMessage()), Set.of());
        }
    }
}
