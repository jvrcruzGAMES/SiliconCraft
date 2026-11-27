package games.jvrcruz.siliconcraft.data;

import games.jvrcruz.siliconcraft.network.PayloadCompression;
import games.jvrcruz.siliconcraft.vm.BinaryBytecode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * Parser, builder, and validator for compiled SiliconCraft {@code .sc} sectioned binary program packages ({@code SCPK} v3).
 *
 * <p>Binary sections (all little-endian):
 * <ul>
 *     <li>{@code manifest} (0x0001, mandatory, must be the first section): stores format header and section directory (ID, flags, offset, size).</li>
 *     <li>{@code metadata} (0x0002, optional): binary metadata about the program (name, target, architecture, required instruction IDs).</li>
 *     <li>{@code program} (0x0003, mandatory): executable 64-bit {@code SCBC} v1 binary image.</li>
 * </ul>
 */
public final class ScProjectFile {
    public static final byte[] MAGIC = {'S', 'C', 'P', 'K'};
    public static final int FORMAT_VERSION = 3;
    public static final int METADATA_VERSION = 1;
    public static final int FLAG_HAS_METADATA = 0x0001;
    public static final int SECTION_MANIFEST = 0x0001;
    public static final int SECTION_METADATA = 0x0002;
    public static final int SECTION_PROGRAM = 0x0003;
    public static final int MAX_NAME_LENGTH = 128;

    private static final int MANIFEST_HEADER_SIZE = 12;
    private static final int SECTION_ENTRY_SIZE = 12;

    private ScProjectFile() {}

    public record SectionEntry(int id, int flags, int offset, int size) {
        public String sectionName() {
            return switch (id) {
                case SECTION_MANIFEST -> "manifest";
                case SECTION_METADATA -> "metadata";
                case SECTION_PROGRAM -> "program";
                default -> "unknown";
            };
        }
    }

    public record Manifest(int version, int flags, List<SectionEntry> sections) {
        public Manifest {
            sections = sections == null ? List.of() : List.copyOf(sections);
        }

        public SectionEntry findSection(int sectionId) {
            for (SectionEntry entry : sections) {
                if (entry.id() == sectionId) {
                    return entry;
                }
            }
            return null;
        }
    }

    public record ParsedProject(
            String name,
            String archName,
            String target,
            byte[] bytecode,
            List<String> requiredInstructions,
            boolean hasMetadata,
            Manifest manifest
    ) {
        public ParsedProject(
                String name,
                String archName,
                String target,
                byte[] bytecode,
                List<String> requiredInstructions
        ) {
            this(name, archName, target, bytecode, requiredInstructions, true, null);
        }

        public ParsedProject {
            name = name == null ? "" : name;
            archName = archName == null ? "" : archName;
            target = target == null ? "" : target;
            bytecode = bytecode == null ? new byte[0] : bytecode;
            requiredInstructions = requiredInstructions == null ? List.of() : List.copyOf(requiredInstructions);
        }

        public int getInstructionCount() {
            return BinaryBytecode.getInstructionCount(bytecode);
        }

        public ProgramEntry toProgramEntry() {
            return new ProgramEntry(
                    UUID.randomUUID().toString(),
                    name,
                    archName,
                    bytecode,
                    requiredInstructions
            );
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ParsedProject other)) return false;
            return hasMetadata == other.hasMetadata
                    && Objects.equals(name, other.name)
                    && Objects.equals(archName, other.archName)
                    && Objects.equals(target, other.target)
                    && Arrays.equals(bytecode, other.bytecode)
                    && Objects.equals(requiredInstructions, other.requiredInstructions)
                    && Objects.equals(manifest, other.manifest);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(name, archName, target, requiredInstructions, hasMetadata, manifest);
            result = 31 * result + Arrays.hashCode(bytecode);
            return result;
        }

        @Override
        public String toString() {
            return "ParsedProject[name=" + name + ", archName=" + archName + ", target=" + target
                    + ", metadata=" + hasMetadata + ", bytes=" + bytecode.length
                    + ", instructions=" + getInstructionCount() + "]";
        }
    }

    public static byte[] buildBytes(
            String name,
            String archName,
            String target,
            byte[] bytecode,
            List<String> requiredInstructions
    ) {
        return buildBytes(name, archName, target, bytecode, requiredInstructions, true);
    }

    public static byte[] buildBytes(
            String name,
            String archName,
            String target,
            byte[] bytecode,
            List<String> requiredInstructions,
            boolean includeMetadata
    ) {
        if (bytecode == null || bytecode.length == 0) {
            throw new IllegalArgumentException("Cannot build .sc package with empty bytecode.");
        }
        BinaryBytecode.decodeScbc(bytecode);
        int instrCount = BinaryBytecode.getInstructionCount(bytecode);
        if (instrCount <= 0) {
            throw new IllegalArgumentException("Cannot build .sc package with 0 instructions.");
        }

        byte[] metadataBytes = includeMetadata
                ? encodeMetadataSection(name, archName, target, bytecode, requiredInstructions, instrCount)
                : null;

        int sectionCount = metadataBytes != null ? 3 : 2;
        int manifestSize = MANIFEST_HEADER_SIZE + sectionCount * SECTION_ENTRY_SIZE;
        int flags = metadataBytes != null ? FLAG_HAS_METADATA : 0;

        List<SectionEntry> entries = new ArrayList<>(sectionCount);
        int cursor = manifestSize;
        entries.add(new SectionEntry(SECTION_MANIFEST, 0, 0, manifestSize));
        if (metadataBytes != null) {
            entries.add(new SectionEntry(SECTION_METADATA, 0, cursor, metadataBytes.length));
            cursor += metadataBytes.length;
        }
        entries.add(new SectionEntry(SECTION_PROGRAM, 0, cursor, bytecode.length));
        cursor += bytecode.length;

        ByteBuffer buf = ByteBuffer.allocate(cursor).order(ByteOrder.LITTLE_ENDIAN);
        // Section 1: manifest (mandatory, first section)
        buf.put(MAGIC);
        buf.putShort((short) FORMAT_VERSION);
        buf.putShort((short) flags);
        buf.putShort((short) sectionCount);
        buf.putShort((short) 0);
        for (SectionEntry entry : entries) {
            buf.putShort((short) entry.id());
            buf.putShort((short) entry.flags());
            buf.putInt(entry.offset());
            buf.putInt(entry.size());
        }
        // Section 2: metadata (optional)
        if (metadataBytes != null) {
            buf.put(metadataBytes);
        }
        // Section 3: program (mandatory)
        buf.put(bytecode);
        return buf.array();
    }

    private static byte[] encodeMetadataSection(
            String name,
            String archName,
            String target,
            byte[] bytecode,
            List<String> requiredInstructions,
            int instrCount
    ) {
        String normName = normalizeProjectName(name);
        String cleanTarget = target == null ? "" : target.trim();
        String cleanArch = archName == null ? "" : archName.trim();
        if (cleanArch.isEmpty()) {
            cleanArch = defaultArchForTarget(cleanTarget);
        }

        byte[] nameBytes = normName.getBytes(StandardCharsets.UTF_8);
        byte[] targetBytes = cleanTarget.getBytes(StandardCharsets.UTF_8);
        byte[] archBytes = cleanArch.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 0xFFFF || targetBytes.length > 0xFFFF || archBytes.length > 0xFFFF) {
            throw new IllegalArgumentException(".sc metadata string exceeds 65535 bytes.");
        }

        Set<Integer> reqIds = new LinkedHashSet<>();
        if (requiredInstructions != null) {
            for (String op : requiredInstructions) {
                if (op != null && !op.isBlank()) {
                    Integer id = BinaryBytecode.OPCODE_MAP.get(op.trim().toUpperCase(Locale.ROOT));
                    if (id == null) {
                        throw new IllegalArgumentException("Unknown required instruction opcode: " + op);
                    }
                    reqIds.add(id);
                }
            }
        }
        if (reqIds.isEmpty()) {
            for (String op : BinaryBytecode.getRequiredInstructions(bytecode)) {
                Integer id = BinaryBytecode.OPCODE_MAP.get(op);
                if (id != null) {
                    reqIds.add(id);
                }
            }
        }
        if (reqIds.size() > 0xFFFF) {
            throw new IllegalArgumentException("Too many required instructions for .sc metadata section.");
        }

        int totalMeta = 8
                + 2 + nameBytes.length
                + 2 + targetBytes.length
                + 2 + archBytes.length
                + 2 + (reqIds.size() * 2);

        ByteBuffer meta = ByteBuffer.allocate(totalMeta).order(ByteOrder.LITTLE_ENDIAN);
        meta.putShort((short) METADATA_VERSION);
        meta.putShort((short) 0);
        meta.putInt(instrCount);

        meta.putShort((short) nameBytes.length);
        meta.put(nameBytes);
        meta.putShort((short) targetBytes.length);
        meta.put(targetBytes);
        meta.putShort((short) archBytes.length);
        meta.put(archBytes);

        meta.putShort((short) reqIds.size());
        for (int id : reqIds) {
            meta.putShort((short) id);
        }
        return meta.array();
    }

    public static Manifest parseManifest(byte[] rawBytes) {
        byte[] pkgBytes = unwrapPackageBytes(rawBytes);
        return parseManifestUnwrapped(pkgBytes);
    }

    public static ParsedProject parseBytes(byte[] rawBytes, String fallbackName) {
        byte[] pkgBytes = unwrapPackageBytes(rawBytes);
        Manifest manifest = parseManifestUnwrapped(pkgBytes);

        SectionEntry programEntry = manifest.findSection(SECTION_PROGRAM);
        if (programEntry == null) {
            throw new IllegalArgumentException("Invalid .sc file: missing mandatory program section.");
        }
        byte[] scbc = Arrays.copyOfRange(pkgBytes, programEntry.offset(), programEntry.offset() + programEntry.size());

        // Validate SCBC v1 payload
        BinaryBytecode.decodeScbc(scbc);
        if (BinaryBytecode.getInstructionCount(scbc) <= 0) {
            throw new IllegalArgumentException("Invalid .sc file: program section contains 0 instructions.");
        }

        SectionEntry metadataEntry = manifest.findSection(SECTION_METADATA);
        if (metadataEntry != null) {
            byte[] metaSlice = Arrays.copyOfRange(pkgBytes, metadataEntry.offset(), metadataEntry.offset() + metadataEntry.size());
            ParsedMetadata meta = parseMetadataSection(metaSlice);
            Set<String> reqSet = new LinkedHashSet<>(meta.requiredInstructions());
            if (reqSet.isEmpty()) {
                reqSet.addAll(BinaryBytecode.getRequiredInstructions(scbc));
            }
            String finalName = normalizeProjectName(
                    (fallbackName != null && !fallbackName.isBlank()) ? fallbackName : meta.name()
            );
            return new ParsedProject(
                    finalName,
                    meta.archName(),
                    meta.target(),
                    scbc,
                    List.copyOf(reqSet),
                    true,
                    manifest
            );
        }

        String finalName = normalizeProjectName(fallbackName);
        Set<String> derivedReq = BinaryBytecode.getRequiredInstructions(scbc);
        return new ParsedProject(
                finalName,
                "Any",
                "",
                scbc,
                List.copyOf(derivedReq),
                false,
                manifest
        );
    }

    private static byte[] unwrapPackageBytes(byte[] rawBytes) {
        if (rawBytes == null || rawBytes.length == 0) {
            throw new IllegalArgumentException("Empty .sc file.");
        }
        if (isGzip(rawBytes)) {
            return gunzipBounded(rawBytes, PayloadCompression.MAX_UNCOMPRESSED_BYTES);
        }
        if (rawBytes.length > PayloadCompression.MAX_UNCOMPRESSED_BYTES) {
            throw new IllegalArgumentException(".sc file exceeds maximum supported size.");
        }
        return rawBytes;
    }

    private static Manifest parseManifestUnwrapped(byte[] pkgBytes) {
        if (pkgBytes.length < MANIFEST_HEADER_SIZE + 2 * SECTION_ENTRY_SIZE) {
            throw new IllegalArgumentException("Invalid .sc file: truncated manifest header.");
        }

        ByteBuffer buf = ByteBuffer.wrap(pkgBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < MAGIC.length; i++) {
            if (buf.get() != MAGIC[i]) {
                throw new IllegalArgumentException("Invalid .sc file: expected binary SCPK v" + FORMAT_VERSION + " package.");
            }
        }

        int version = Short.toUnsignedInt(buf.getShort());
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported .sc format version: " + version);
        }
        int flags = Short.toUnsignedInt(buf.getShort());
        int sectionCount = Short.toUnsignedInt(buf.getShort());
        buf.getShort(); // reserved
        if (sectionCount < 2 || sectionCount > 3) {
            throw new IllegalArgumentException("Invalid .sc manifest: expected 2 or 3 sections, found " + sectionCount + ".");
        }

        int manifestSize = MANIFEST_HEADER_SIZE + sectionCount * SECTION_ENTRY_SIZE;
        if (pkgBytes.length < manifestSize) {
            throw new IllegalArgumentException("Invalid .sc file: truncated manifest section table.");
        }

        List<SectionEntry> sections = new ArrayList<>(sectionCount);
        Set<Integer> seenIds = new LinkedHashSet<>();
        for (int i = 0; i < sectionCount; i++) {
            int id = Short.toUnsignedInt(buf.getShort());
            int secFlags = Short.toUnsignedInt(buf.getShort());
            long offsetLong = Integer.toUnsignedLong(buf.getInt());
            long sizeLong = Integer.toUnsignedLong(buf.getInt());

            if (id != SECTION_MANIFEST && id != SECTION_METADATA && id != SECTION_PROGRAM) {
                throw new IllegalArgumentException("Invalid .sc manifest: unknown section ID 0x" + Integer.toHexString(id).toUpperCase(Locale.ROOT) + ".");
            }
            if (!seenIds.add(id)) {
                throw new IllegalArgumentException("Invalid .sc manifest: duplicate section ID 0x" + Integer.toHexString(id).toUpperCase(Locale.ROOT) + ".");
            }
            if (i == 0 && id != SECTION_MANIFEST) {
                throw new IllegalArgumentException("Invalid .sc manifest: manifest must be the first section.");
            }
            if (offsetLong + sizeLong > pkgBytes.length) {
                throw new IllegalArgumentException("Invalid .sc manifest: section bounds exceed file size.");
            }
            if (id == SECTION_MANIFEST) {
                if (offsetLong != 0 || sizeLong != manifestSize) {
                    throw new IllegalArgumentException("Invalid .sc manifest: manifest section bounds do not match first-section layout.");
                }
            } else {
                if (offsetLong < manifestSize) {
                    throw new IllegalArgumentException("Invalid .sc manifest: section overlaps manifest.");
                }
                if (sizeLong == 0) {
                    throw new IllegalArgumentException("Invalid .sc manifest: non-manifest section cannot be empty.");
                }
            }

            sections.add(new SectionEntry(id, secFlags, (int) offsetLong, (int) sizeLong));
        }

        if (!seenIds.contains(SECTION_PROGRAM)) {
            throw new IllegalArgumentException("Invalid .sc manifest: missing mandatory program section.");
        }

        // Verify non-manifest sections do not overlap one another
        for (int a = 0; a < sections.size(); a++) {
            SectionEntry sa = sections.get(a);
            if (sa.id() == SECTION_MANIFEST) continue;
            long aStart = Integer.toUnsignedLong(sa.offset());
            long aEnd = aStart + Integer.toUnsignedLong(sa.size());
            for (int b = a + 1; b < sections.size(); b++) {
                SectionEntry sb = sections.get(b);
                if (sb.id() == SECTION_MANIFEST) continue;
                long bStart = Integer.toUnsignedLong(sb.offset());
                long bEnd = bStart + Integer.toUnsignedLong(sb.size());
                if (aStart < bEnd && bStart < aEnd) {
                    throw new IllegalArgumentException("Invalid .sc manifest: sections '"
                            + sa.sectionName() + "' and '" + sb.sectionName() + "' overlap.");
                }
            }
        }

        return new Manifest(version, flags, sections);
    }

    private record ParsedMetadata(String name, String target, String archName, List<String> requiredInstructions) {}

    private static ParsedMetadata parseMetadataSection(byte[] metaBytes) {
        if (metaBytes.length < 16) {
            throw new IllegalArgumentException("Invalid .sc metadata section: truncated header.");
        }
        ByteBuffer buf = ByteBuffer.wrap(metaBytes).order(ByteOrder.LITTLE_ENDIAN);
        int metaVersion = Short.toUnsignedInt(buf.getShort());
        if (metaVersion != METADATA_VERSION) {
            throw new IllegalArgumentException("Unsupported .sc metadata section version: " + metaVersion + ".");
        }
        buf.getShort(); // meta flags
        buf.getInt(); // instruction count

        String rawName = readUtf8Short(buf, "name");
        String target = readUtf8Short(buf, "target").trim();
        String archName = readUtf8Short(buf, "arch").trim();
        if (archName.isEmpty()) {
            archName = defaultArchForTarget(target);
        }

        if (buf.remaining() < 2) {
            throw new IllegalArgumentException("Invalid .sc metadata section: missing required instruction table.");
        }
        int reqCount = Short.toUnsignedInt(buf.getShort());
        if ((long) reqCount * 2L > buf.remaining()) {
            throw new IllegalArgumentException("Invalid .sc metadata section: truncated required instruction table.");
        }
        List<String> reqList = new ArrayList<>(reqCount);
        for (int i = 0; i < reqCount; i++) {
            int opcodeId = Short.toUnsignedInt(buf.getShort());
            String opName = BinaryBytecode.getOpcodeName(opcodeId);
            if (opName == null) {
                throw new IllegalArgumentException("Invalid .sc metadata section: unknown opcode ID " + opcodeId + ".");
            }
            reqList.add(opName);
        }
        return new ParsedMetadata(rawName, target, archName, reqList);
    }

    private static String defaultArchForTarget(String target) {
        return switch (target.toLowerCase(Locale.ROOT)) {
            case "n2" -> "N2";
            case "asm", "assembly" -> "ASM";
            default -> "Any";
        };
    }

    private static String readUtf8Short(ByteBuffer buf, String fieldName) {
        if (buf.remaining() < 2) {
            throw new IllegalArgumentException("Invalid .sc file: truncated " + fieldName + " length.");
        }
        int len = Short.toUnsignedInt(buf.getShort());
        if (len > buf.remaining()) {
            throw new IllegalArgumentException("Invalid .sc file: truncated " + fieldName + " data.");
        }
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
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
