package games.jvrcruz.siliconcraft.compiler;

import java.util.*;

public class NProjectResolver {

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

    public static String cleanImportString(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            if (s.length() >= 2) {
                s = s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }

    public static String resolveImportPath(String currentFile, String importStr, Set<String> availableFiles) {
        String clean = cleanImportString(importStr);
        if (clean.isEmpty()) return null;

        String currentDir = "";
        int lastSlash = currentFile.lastIndexOf('/');
        if (lastSlash != -1) {
            currentDir = currentFile.substring(0, lastSlash);
        }

        List<String> candidates = new ArrayList<>();

        // 1. Relative to current file's directory
        String rel = currentDir.isEmpty() ? clean : currentDir + "/" + clean;
        candidates.add(normalizePath(rel));
        if (!clean.endsWith(".n")) {
            candidates.add(normalizePath(rel + ".n"));
        }

        // 2. Relative to project root
        candidates.add(normalizePath(clean));
        if (!clean.endsWith(".n")) {
            candidates.add(normalizePath(clean + ".n"));
        }

        // 3. Dot notation to slash (e.g. math.vec -> math/vec.n)
        if (clean.contains(".") && !clean.endsWith(".n")) {
            String slash = clean.replace('.', '/');
            candidates.add(normalizePath(currentDir.isEmpty() ? slash + ".n" : currentDir + "/" + slash + ".n"));
            candidates.add(normalizePath(slash + ".n"));
        }

        // Check exact matches
        for (String cand : candidates) {
            if (availableFiles.contains(cand)) {
                return cand;
            }
        }

        // Check case-insensitive matches
        for (String cand : candidates) {
            for (String file : availableFiles) {
                if (file.equalsIgnoreCase(cand)) {
                    return file;
                }
            }
        }

        return null;
    }

    public static void expandProjectFiles(String currentFile,
                                         Map<String, List<String>> fileMap,
                                         Set<String> alreadyImported,
                                         Set<String> currentlyImporting,
                                         List<NCompiler.SourceLine> outputLines,
                                         List<String> errors) {
        currentlyImporting.add(currentFile);
        List<String> lines = fileMap.get(currentFile);
        if (lines == null) {
            errors.add("Cannot read source lines for file: " + currentFile);
            currentlyImporting.remove(currentFile);
            return;
        }

        for (int i = 0; i < lines.size(); i++) {
            String rawLine = lines.get(i);
            String line = InstructionValidator.stripComments(rawLine).trim();

            if (line.startsWith("import ") || line.startsWith("import\t")) {
                String importArg = line.substring(6).trim();
                String resolved = resolveImportPath(currentFile, importArg, fileMap.keySet());
                if (resolved == null) {
                    errors.add(currentFile + ":" + (i + 1) + ": Cannot resolve import '" + importArg + "'");
                    continue;
                }

                if (currentlyImporting.contains(resolved)) {
                    errors.add(currentFile + ":" + (i + 1) + ": Circular import detected: "
                            + String.join(" -> ", currentlyImporting) + " -> " + resolved);
                    continue;
                }

                if (alreadyImported.contains(resolved)) {
                    // Already imported: skip duplicate inclusion
                    continue;
                }

                expandProjectFiles(resolved, fileMap, alreadyImported, currentlyImporting, outputLines, errors);
            } else {
                outputLines.add(new NCompiler.SourceLine(currentFile, i + 1, rawLine));
            }
        }

        currentlyImporting.remove(currentFile);
        alreadyImported.add(currentFile);
    }

    public static String getAsmNameFromZip(String zipFileName) {
        if (zipFileName == null || zipFileName.isBlank()) return "project.asm";
        String name = zipFileName.trim();
        int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSlash != -1) {
            name = name.substring(lastSlash + 1);
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            name = name.substring(0, name.length() - 4);
        }
        return name + ".asm";
    }
}
