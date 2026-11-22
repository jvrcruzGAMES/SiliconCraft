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

    public static boolean isAssemblyFile(String path) {
        if (path == null) return false;
        String p = path.toLowerCase(Locale.ROOT);
        return p.endsWith(".asm") || p.endsWith(".s");
    }

    public static boolean isNFile(String path) {
        if (path == null) return false;
        return path.toLowerCase(Locale.ROOT).endsWith(".n");
    }

    public static boolean isSupportedSourceFile(String path) {
        return isNFile(path) || isAssemblyFile(path);
    }

    public static String getImportDirectiveArg(String rawLine) {
        if (rawLine == null) return null;
        String line = InstructionValidator.stripComments(rawLine).trim();
        if (line.isEmpty()) return null;

        if (line.startsWith(".include ") || line.startsWith(".include\t")) {
            return line.substring(9).trim();
        }
        if (line.startsWith("include ") || line.startsWith("include\t")) {
            return line.substring(8).trim();
        }
        if (line.startsWith("#include ") || line.startsWith("#include\t")) {
            String s = line.substring(9).trim();
            if (s.startsWith("<") && s.endsWith(">") && s.length() >= 2) {
                s = s.substring(1, s.length() - 1).trim();
            }
            return s;
        }
        if (line.startsWith(".import ") || line.startsWith(".import\t")) {
            return line.substring(8).trim();
        }
        if (line.startsWith("import ") || line.startsWith("import\t")) {
            return line.substring(7).trim();
        }
        return null;
    }

    public static String cleanImportString(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))
                || (s.startsWith("<") && s.endsWith(">"))) {
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
        String[] extensions = {"", ".n", ".asm", ".s", ".S"};

        // 1. Relative to current file's directory
        String rel = currentDir.isEmpty() ? clean : currentDir + "/" + clean;
        for (String ext : extensions) {
            candidates.add(normalizePath(rel + ext));
        }

        // 2. Relative to project root
        for (String ext : extensions) {
            candidates.add(normalizePath(clean + ext));
        }

        // 3. Dot notation to slash (e.g. math.vec -> math/vec.n or math/vec.asm)
        if (clean.contains(".") && !clean.endsWith(".n") && !clean.endsWith(".asm") && !clean.endsWith(".s") && !clean.endsWith(".S")) {
            String slash = clean.replace('.', '/');
            for (String ext : extensions) {
                if (!ext.isEmpty()) {
                    candidates.add(normalizePath(currentDir.isEmpty() ? slash + ext : currentDir + "/" + slash + ext));
                    candidates.add(normalizePath(slash + ext));
                }
            }
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
        expandProjectFiles(currentFile, fileMap, alreadyImported, currentlyImporting, outputLines, errors, true, new int[]{1});
    }

    public static void expandProjectFiles(String currentFile,
                                         Map<String, List<String>> fileMap,
                                         Set<String> alreadyImported,
                                         Set<String> currentlyImporting,
                                         List<NCompiler.SourceLine> outputLines,
                                         List<String> errors,
                                         boolean isEntrypoint,
                                         int[] wrapCounter) {
        currentlyImporting.add(currentFile);
        List<String> lines = fileMap.get(currentFile);
        if (lines == null) {
            errors.add("Cannot read source lines for file: " + currentFile);
            currentlyImporting.remove(currentFile);
            return;
        }

        // If an assembly module is imported (not the main entrypoint), wrap it with a JMP
        // so its subroutines are not executed as linear top-level code on machine startup
        boolean wrapWithJump = !isEntrypoint && isAssemblyFile(currentFile);
        int wrapId = 0;
        if (wrapWithJump) {
            wrapId = wrapCounter[0]++;
            outputLines.add(new NCompiler.SourceLine(currentFile, 0, "JMP _import_skip_" + wrapId));
        }

        for (int i = 0; i < lines.size(); i++) {
            String rawLine = lines.get(i);
            String importArg = getImportDirectiveArg(rawLine);

            if (importArg != null) {
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

                expandProjectFiles(resolved, fileMap, alreadyImported, currentlyImporting, outputLines, errors, false, wrapCounter);
            } else {
                outputLines.add(new NCompiler.SourceLine(currentFile, i + 1, rawLine));
            }
        }

        if (wrapWithJump) {
            outputLines.add(new NCompiler.SourceLine(currentFile, lines.size() + 1, "_import_skip_" + wrapId + ":"));
        }

        currentlyImporting.remove(currentFile);
        alreadyImported.add(currentFile);
    }

    public static String getAsmNameFromZip(String zipFileName) {
        if (zipFileName == null || zipFileName.isBlank()) return "project.o";
        String name = zipFileName.trim();
        int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSlash != -1) {
            name = name.substring(lastSlash + 1);
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            name = name.substring(0, name.length() - 4);
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(".asm") || name.toLowerCase(Locale.ROOT).endsWith(".s")) {
            name = name.substring(0, name.lastIndexOf('.'));
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(".n")) {
            name = name.substring(0, name.lastIndexOf('.'));
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(".o")) {
            return name;
        }
        return name + ".o";
    }

    public static Map<String, List<String>> loadProjectFromFolder(java.io.File folder) throws java.io.IOException {
        if (folder == null || !folder.exists() || !folder.isDirectory()) {
            return Collections.emptyMap();
        }
        java.nio.file.Path rootPath = folder.toPath().toAbsolutePath().normalize();
        Map<String, List<String>> files = new LinkedHashMap<>();
        try (var stream = java.nio.file.Files.walk(rootPath)) {
            List<java.nio.file.Path> projectFiles = stream.filter(java.nio.file.Files::isRegularFile)
                    .filter(p -> isSupportedSourceFile(p.getFileName().toString()))
                    .toList();
            for (java.nio.file.Path filePath : projectFiles) {
                java.nio.file.Path rel = rootPath.relativize(filePath);
                String normPath = rel.toString().replace('\\', '/');
                while (normPath.startsWith("/")) {
                    normPath = normPath.substring(1);
                }
                List<String> rawLines = java.nio.file.Files.readAllLines(filePath, java.nio.charset.StandardCharsets.UTF_8);
                List<String> cleaned = new ArrayList<>();
                for (String line : rawLines) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        cleaned.add(trimmed);
                    }
                }
                files.put(normPath, cleaned);
            }
        }
        return files;
    }

    public static Map<String, List<String>> loadProjectFromZip(java.io.File zipFile) throws java.io.IOException {
        if (zipFile == null || !zipFile.exists() || !zipFile.isFile()) {
            return Collections.emptyMap();
        }
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(zipFile.toPath())) {
            return loadProjectFromZipStream(in);
        }
    }

    public static Map<String, List<String>> loadProjectFromZipStream(java.io.InputStream in) throws java.io.IOException {
        Map<String, List<String>> extracted = new LinkedHashMap<>();
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(in, java.nio.charset.StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[4096];
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory() && isSupportedSourceFile(entry.getName())) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }
                    String text = baos.toString(java.nio.charset.StandardCharsets.UTF_8);
                    List<String> rawLines = Arrays.asList(text.split("\\r?\\n"));
                    List<String> cleaned = new ArrayList<>();
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
        return extracted;
    }
}
