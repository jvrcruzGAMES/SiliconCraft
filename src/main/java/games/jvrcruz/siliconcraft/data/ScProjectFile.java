package games.jvrcruz.siliconcraft.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import games.jvrcruz.siliconcraft.compiler.InstructionValidator;
import games.jvrcruz.siliconcraft.network.PayloadCompression;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * Parser and validator for compiled SiliconCraft {@code .sc} program packages.
 */
public final class ScProjectFile {
    public static final String FORMAT_ID = "siliconcraft-sc";
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_NAME_LENGTH = 128;

    private ScProjectFile() {}

    public record ParsedProject(
            String name,
            String archName,
            String target,
            List<String> bytecode,
            List<String> requiredInstructions
    ) {
        public ProgramEntry toProgramEntry() {
            return new ProgramEntry(
                    UUID.randomUUID().toString(),
                    name,
                    archName,
                    bytecode,
                    requiredInstructions
            );
        }
    }

    public static ParsedProject parseBytes(byte[] rawBytes, String fallbackName) {
        if (rawBytes == null || rawBytes.length == 0) {
            throw new IllegalArgumentException("Empty .sc file.");
        }

        byte[] jsonBytes;
        if (isGzip(rawBytes)) {
            jsonBytes = gunzipBounded(rawBytes, PayloadCompression.MAX_UNCOMPRESSED_BYTES);
        } else {
            if (rawBytes.length > PayloadCompression.MAX_UNCOMPRESSED_BYTES) {
                throw new IllegalArgumentException(".sc file exceeds maximum supported size.");
            }
            jsonBytes = rawBytes;
        }

        String jsonText = new String(jsonBytes, StandardCharsets.UTF_8);
        return parseJson(jsonText, fallbackName);
    }

    public static ParsedProject parseJson(String jsonText, String fallbackName) {
        if (jsonText == null || jsonText.isBlank()) {
            throw new IllegalArgumentException("Empty .sc file.");
        }

        JsonElement rootElement;
        try {
            rootElement = JsonParser.parseString(jsonText);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("Malformed .sc JSON: " + e.getMessage(), e);
        }

        if (rootElement == null || !rootElement.isJsonObject()) {
            throw new IllegalArgumentException("Invalid .sc file: root must be a JSON object.");
        }
        JsonObject root = rootElement.getAsJsonObject();

        if (!root.has("format") || !root.get("format").isJsonPrimitive()
                || !FORMAT_ID.equals(root.get("format").getAsString())) {
            throw new IllegalArgumentException("Invalid .sc file: expected format '" + FORMAT_ID + "'.");
        }

        if (!root.has("version") || !root.get("version").isJsonPrimitive()
                || !root.get("version").getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Invalid .sc file: missing numeric 'version'.");
        }
        int version = root.get("version").getAsInt();
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported .sc format version: " + version);
        }

        if (!root.has("bytecode") || !root.get("bytecode").isJsonArray()) {
            throw new IllegalArgumentException("Invalid .sc file: missing 'bytecode' array.");
        }
        JsonArray bytecodeArray = root.getAsJsonArray("bytecode");
        if (bytecodeArray.isEmpty()) {
            throw new IllegalArgumentException("Invalid .sc file: 'bytecode' array is empty.");
        }

        List<String> bytecode = new ArrayList<>(bytecodeArray.size());
        for (int i = 0; i < bytecodeArray.size(); i++) {
            JsonElement el = bytecodeArray.get(i);
            if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("Invalid .sc bytecode entry at index " + i + ".");
            }
            bytecode.add(el.getAsString());
        }

        Set<String> reqSet = new LinkedHashSet<>();
        if (root.has("requiredInstructions") && root.get("requiredInstructions").isJsonArray()) {
            JsonArray reqArray = root.getAsJsonArray("requiredInstructions");
            for (JsonElement el : reqArray) {
                if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                    String op = el.getAsString().trim().toUpperCase(Locale.ROOT);
                    if (!op.isEmpty()) {
                        reqSet.add(op);
                    }
                }
            }
        }
        if (reqSet.isEmpty()) {
            reqSet.addAll(InstructionValidator.getRequiredInstructions(bytecode));
        }

        String rawName = null;
        if (root.has("name") && root.get("name").isJsonPrimitive() && root.get("name").getAsJsonPrimitive().isString()) {
            rawName = root.get("name").getAsString();
        }
        String finalName = normalizeProjectName(
                (fallbackName != null && !fallbackName.isBlank()) ? fallbackName : rawName
        );

        String target = "";
        if (root.has("target") && root.get("target").isJsonPrimitive() && root.get("target").getAsJsonPrimitive().isString()) {
            target = root.get("target").getAsString().trim();
        }

        String archName = "";
        if (root.has("arch") && root.get("arch").isJsonPrimitive() && root.get("arch").getAsJsonPrimitive().isString()) {
            archName = root.get("arch").getAsString().trim();
        }
        if (archName.isEmpty()) {
            archName = switch (target.toLowerCase(Locale.ROOT)) {
                case "n2" -> "N2";
                case "asm", "assembly" -> "ASM";
                default -> "Any";
            };
        }

        return new ParsedProject(
                finalName,
                archName,
                target,
                List.copyOf(bytecode),
                List.copyOf(reqSet)
        );
    }

    public static String validateRenameName(String rawNewName, java.util.Collection<String> otherExistingNames) {
        String candidate = rawNewName == null ? "" : rawNewName.trim();
        if (candidate.isEmpty()) {
            throw new IllegalArgumentException("Project name cannot be empty.");
        }
        if (candidate.contains("/") || candidate.contains("\\")) {
            throw new IllegalArgumentException("Project name cannot contain path separators.");
        }
        if (candidate.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Project name is too long (max " + MAX_NAME_LENGTH + " chars).");
        }
        if (otherExistingNames != null) {
            for (String existing : otherExistingNames) {
                if (existing != null && existing.equalsIgnoreCase(candidate)) {
                    throw new IllegalArgumentException("A project named '" + candidate + "' already exists.");
                }
            }
        }
        return candidate;
    }

    public static String normalizeProjectName(String candidate) {
        String name = candidate == null ? "" : candidate.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1).trim();
        }
        if (name.isEmpty()) {
            name = "program.sc";
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".sc")) {
            name = name + ".sc";
        }
        if (name.length() > MAX_NAME_LENGTH) {
            String base = name.substring(0, MAX_NAME_LENGTH - 3);
            name = base + ".sc";
        }
        return name;
    }

    public static boolean isGzip(byte[] data) {
        return data.length >= 2 && (data[0] == (byte) 0x1F) && (data[1] == (byte) 0x8B);
    }

    private static byte[] gunzipBounded(byte[] compressed, int maxBytes) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(compressed.length * 4, 65536))) {
            byte[] buf = new byte[16 * 1024];
            int total = 0;
            int read;
            while ((read = gzip.read(buf)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IllegalArgumentException("Decompressed .sc file exceeds limit (" + (maxBytes / (1024 * 1024)) + " MB).");
                }
                out.write(buf, 0, read);
            }
            return out.toByteArray();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid compressed .sc file: " + e.getMessage(), e);
        }
    }
}
