package games.jvrcruz.siliconcraft.compiler;

import java.util.LinkedHashMap;
import java.util.Map;

public class NType {
    public enum Type {
        INT,
        CHAR,
        STRING,
        BOOLEAN,
        TABLE
    }

    public static class FieldInfo {
        public final int offset;
        public final Type type;

        public FieldInfo(int offset, Type type) {
            this.offset = offset;
            this.type = type;
        }
    }

    public static class TableInfo {
        public final int baseAddr;
        public int capacity;
        public int nextOffset;
        public final Map<String, FieldInfo> fields = new LinkedHashMap<>();

        public TableInfo(int baseAddr, int capacity) {
            this.baseAddr = baseAddr;
            this.capacity = capacity;
            this.nextOffset = 0;
        }
    }
}
