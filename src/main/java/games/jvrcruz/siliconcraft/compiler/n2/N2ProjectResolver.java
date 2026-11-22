package games.jvrcruz.siliconcraft.compiler.n2;

import java.util.*;

public class N2ProjectResolver {

    public static String normalizePath(String path) {
        if (path == null) return "";
        path = path.replace('\\', '/').trim();
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        String[] parts = path.split("/");
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) {
                if (!list.isEmpty() && !list.get(list.size() - 1).equals("..")) {
                    list.remove(list.size() - 1);
                }
            } else {
                list.add(p);
            }
        }
        return String.join("/", list);
    }

    public static String resolveImportPath(String currentFile, String importStr, Set<String> availableFiles) {
        if (importStr == null || importStr.trim().isEmpty()) return null;
        String clean = importStr.trim();
        while (clean.startsWith("\"") && clean.endsWith("\"") && clean.length() >= 2) {
            clean = clean.substring(1, clean.length() - 1).trim();
        }

        String currentDir = "";
        int lastSlash = currentFile.lastIndexOf('/');
        if (lastSlash != -1) {
            currentDir = currentFile.substring(0, lastSlash);
        }

        List<String> candidates = new ArrayList<>();
        String[] extensions = {"", ".n2", ".n", ".asm", ".s", ".S"};

        // 1. Relative to current file's directory
        String rel = currentDir.isEmpty() ? clean : currentDir + "/" + clean;
        for (String ext : extensions) {
            candidates.add(normalizePath(rel + ext));
        }

        // 2. Relative to project root
        for (String ext : extensions) {
            candidates.add(normalizePath(clean + ext));
        }

        // Check against available files
        for (String cand : candidates) {
            if (availableFiles.contains(cand)) {
                return cand;
            }
        }

        // Case-insensitive fallback
        for (String cand : candidates) {
            for (String file : availableFiles) {
                if (file.equalsIgnoreCase(cand)) {
                    return file;
                }
            }
        }

        return null;
    }

    public static N2AST.ProgramNode resolveAndMergeProject(
            String entrypoint,
            Map<String, List<String>> files,
            List<String> errors
    ) {
        Set<String> alreadyImported = new HashSet<>();
        Set<String> currentlyImporting = new LinkedHashSet<>();
        N2AST.ProgramNode mergedProgram = new N2AST.ProgramNode(1, 1);
        Set<String> seenLibImports = new HashSet<>();

        resolveFileRecursive(entrypoint, files, alreadyImported, currentlyImporting, mergedProgram, seenLibImports, errors);
        return mergedProgram;
    }

    private static void resolveFileRecursive(
            String currentFile,
            Map<String, List<String>> files,
            Set<String> alreadyImported,
            Set<String> currentlyImporting,
            N2AST.ProgramNode mergedProgram,
            Set<String> seenLibImports,
            List<String> errors
    ) {
        if (currentlyImporting.contains(currentFile)) {
            errors.add("Circular import detected: " + String.join(" -> ", currentlyImporting) + " -> " + currentFile);
            return;
        }

        if (alreadyImported.contains(currentFile)) {
            return;
        }

        currentlyImporting.add(currentFile);

        List<String> lines = files.get(currentFile);
        if (lines == null) {
            errors.add("Project file not found: " + currentFile);
            currentlyImporting.remove(currentFile);
            return;
        }

        // Check if current file is assembly
        String lower = currentFile.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".asm") || lower.endsWith(".s")) {
            mergedProgram.statements.add(new N2AST.InlineAssemblyNode(new ArrayList<>(lines), 1, 1));
            currentlyImporting.remove(currentFile);
            alreadyImported.add(currentFile);
            return;
        }

        // Parse N2 file
        String source = String.join("\n", lines);
        N2AST.ProgramNode fileAst;
        try {
            N2Lexer lexer = new N2Lexer(source, currentFile);
            List<N2Token> tokens = lexer.tokenize();
            N2Parser parser = new N2Parser(tokens, currentFile);
            fileAst = parser.parse();
        } catch (N2CompileException e) {
            errors.add(e.getMessage());
            currentlyImporting.remove(currentFile);
            return;
        } catch (Exception e) {
            errors.add("Error parsing " + currentFile + ": " + e.getMessage());
            currentlyImporting.remove(currentFile);
            return;
        }

        // Process imports in fileAst
        for (N2AST.ImportNode imp : fileAst.imports) {
            if (imp.isLibrary) {
                // Deduplicate library imports
                String key = imp.path + ":" + String.join(",", imp.symbols);
                if (!seenLibImports.contains(key)) {
                    seenLibImports.add(key);
                    mergedProgram.imports.add(imp);
                }
            } else {
                // Project file import
                String resolvedTarget = resolveImportPath(currentFile, imp.path, files.keySet());
                if (resolvedTarget == null) {
                    errors.add(currentFile + ":" + imp.getLine() + ": Cannot resolve project import '\"" + imp.path + "\"'");
                } else {
                    resolveFileRecursive(resolvedTarget, files, alreadyImported, currentlyImporting, mergedProgram, seenLibImports, errors);
                }
            }
        }

        // Append fileAst statements
        mergedProgram.statements.addAll(fileAst.statements);

        currentlyImporting.remove(currentFile);
        alreadyImported.add(currentFile);
    }
}
