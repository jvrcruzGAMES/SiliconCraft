package games.jvrcruz.siliconcraft.client;

import games.jvrcruz.siliconcraft.data.ProgramEntry;

import java.util.ArrayList;
import java.util.List;

public class ClientProgramManager {
    public static final List<ProgramEntry> PROGRAMS = new ArrayList<>();

    public static void setPrograms(List<ProgramEntry> list) {
        PROGRAMS.clear();
        if (list != null) {
            PROGRAMS.addAll(list);
        }
    }
}
