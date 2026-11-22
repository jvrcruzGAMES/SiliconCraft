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

    public static Map<String, List<String>> loadProjectFromFolder(java.io.File folder) throws java.io.IOException {
        if (folder == null || !folder.exists() || !folder.isDirectory()) {
            return Collections.emptyMap();
        }
        java.nio.file.Path rootPath = folder.toPath().toAbsolutePath().normalize();
        Map<String, List<String>> files = new LinkedHashMap<>();
        try (var stream = java.nio.file.Files.walk(rootPath)) {
            List<java.nio.file.Path> nFiles = stream.filter(java.nio.file.Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".n"))
                    .toList();
            for (java.nio.file.Path filePath : nFiles) {
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
                if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".n")) {
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
