package games.jvrcruz.siliconcraft.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

public class ProgramSavedData extends SavedData {
    private static final String DATA_NAME = "siliconcraft_programs";
    private final List<ProgramEntry> programs = new ArrayList<>();
    private boolean defaultsInitialized = false;

    public record RenameResult(boolean success, String finalName, String error) {}

    public ProgramSavedData() {
        if (!defaultsInitialized) {
            initDefaultPrograms();
            defaultsInitialized = true;
        }
    }

    private void initDefaultPrograms() {
        if (programs.isEmpty()) {
            programs.add(new ProgramEntry(
                    "demo_counter",
                    "counter.sc",
                    "Any",
                    List.of(
                            "; Countdown demo",
                            "MOV A, 5",
                            "loop:",
                            "OUT A",
                            "DEC A",
                            "JNZ loop",
                            "OUT \"Liftoff!\"",
                            "HLT"
                    ),
                    List.of("MOV", "OUT", "DEC", "JNZ", "HLT")
            ));
            programs.add(new ProgramEntry(
                    "demo_fibonacci",
                    "fibonacci.sc",
                    "Any",
                    List.of(
                            "; Fibonacci sequence",
                            "MOV A, 0",
                            "MOV B, 1",
                            "MOV C, 6",
                            "loop:",
                            "OUT A",
                            "MOV [0], A",
                            "ADD [0], B",
                            "MOV A, B",
                            "MOV B, [0]",
                            "DEC C",
                            "JNZ loop",
                            "HLT"
                    ),
                    List.of("MOV", "OUT", "ADD", "DEC", "JNZ", "HLT")
            ));
        }
    }

    public List<ProgramEntry> getPrograms() {
        return programs;
    }

    public void addProgram(ProgramEntry entry) {
        programs.removeIf(p -> p.name().equalsIgnoreCase(entry.name()));
        programs.add(entry);
        setDirty();
    }

    public RenameResult renameProgram(String id, String rawNewName) {
        if (id == null || id.isBlank()) {
            return new RenameResult(false, "", "Missing project identifier.");
        }

        int targetIndex = -1;
        for (int i = 0; i < programs.size(); i++) {
            ProgramEntry p = programs.get(i);
            if (p.id().equals(id) || p.name().equalsIgnoreCase(id)) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex == -1) {
            return new RenameResult(false, "", "Project not found.");
        }

        List<String> otherNames = new ArrayList<>();
        for (int i = 0; i < programs.size(); i++) {
            if (i != targetIndex) {
                otherNames.add(programs.get(i).name());
            }
        }

        String candidate;
        try {
            candidate = ScProjectFile.validateRenameName(rawNewName, otherNames);
        } catch (IllegalArgumentException e) {
            return new RenameResult(false, "", e.getMessage());
        }

        ProgramEntry target = programs.get(targetIndex);
        programs.set(targetIndex, new ProgramEntry(
                target.id(),
                candidate,
                target.archName(),
                target.lines(),
                target.requiredInstructions()
        ));
        setDirty();
        return new RenameResult(true, candidate, null);
    }

    public boolean removeProgram(String id) {
        if (id == null || id.isEmpty()) return false;
        boolean removed = programs.removeIf(p -> p.id().equals(id) || p.name().equalsIgnoreCase(id));
        if (removed) {
            setDirty();
        }
        return removed;
    }

    public static ProgramSavedData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new Factory<>(ProgramSavedData::new, ProgramSavedData::load, null),
                DATA_NAME
        );
    }

    public static ProgramSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ProgramSavedData data = new ProgramSavedData();
        data.programs.clear();
        data.defaultsInitialized = tag.getBoolean("DefaultsInitialized");

        if (tag.contains("Programs", Tag.TAG_LIST)) {
            ListTag list = tag.getList("Programs", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag pTag = list.getCompound(i);
                String id = pTag.getString("Id");
                String name = pTag.getString("Name");
                String archName = pTag.getString("ArchName");

                List<String> lines = new ArrayList<>();
                ListTag linesTag = pTag.getList("Lines", Tag.TAG_STRING);
                for (int j = 0; j < linesTag.size(); j++) {
                    lines.add(linesTag.getString(j));
                }

                List<String> req = new ArrayList<>();
                ListTag reqTag = pTag.getList("Required", Tag.TAG_STRING);
                for (int j = 0; j < reqTag.size(); j++) {
                    req.add(reqTag.getString(j));
                }

                data.programs.add(new ProgramEntry(id, name, archName, lines, req));
            }
        }
        if (!data.defaultsInitialized && data.programs.isEmpty()) {
            data.initDefaultPrograms();
            data.defaultsInitialized = true;
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("DefaultsInitialized", defaultsInitialized);
        ListTag list = new ListTag();
        for (ProgramEntry p : programs) {
            CompoundTag pTag = new CompoundTag();
            pTag.putString("Id", p.id());
            pTag.putString("Name", p.name());
            pTag.putString("ArchName", p.archName());

            ListTag linesTag = new ListTag();
            for (String l : p.lines()) linesTag.add(StringTag.valueOf(l));
            pTag.put("Lines", linesTag);

            ListTag reqTag = new ListTag();
            for (String r : p.requiredInstructions()) reqTag.add(StringTag.valueOf(r));
            pTag.put("Required", reqTag);

            list.add(pTag);
        }
        tag.put("Programs", list);
        return tag;
    }
}
