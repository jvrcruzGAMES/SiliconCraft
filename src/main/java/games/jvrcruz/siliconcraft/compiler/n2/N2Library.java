package games.jvrcruz.siliconcraft.compiler.n2;

import java.util.*;

public class N2Library {

    public enum SymbolType {
        FUNCTION,
        CONSTANT
    }

    public record SymbolInfo(String name, SymbolType type, Integer intValue, int arity, String library) {}

    private static final Map<String, Map<String, SymbolInfo>> LIBRARIES = new LinkedHashMap<>();
    private static final Map<String, String> SYMBOL_TO_LIB = new HashMap<>();

    static {
        // --- 1. <std> (Merged standard library: I/O, Time, and Utilities) ---
        Map<String, SymbolInfo> std = new LinkedHashMap<>();
        // I/O & Console
        addFunc(std, "print", 1, "std");
        addFunc(std, "println", 1, "std");
        addFunc(std, "write", 1, "std");
        addFunc(std, "cls", 0, "std");
        addFunc(std, "out", 2, "std");
        addFunc(std, "read", 0, "std");
        // Time & Timer
        addFunc(std, "time", 0, "std");
        addFunc(std, "sleep", 1, "std");
        addFunc(std, "set_timer", 1, "std");
        // General Utilities
        addFunc(std, "abs", 1, "std");
        addFunc(std, "min", 2, "std");
        addFunc(std, "max", 2, "std");
        addFunc(std, "clamp", 3, "std");
        addFunc(std, "assert", 1, "std");

        registerLib("std", std);
        registerLib("lib/std", std);
        registerLib("io", std);
        registerLib("lib/io", std);
        registerLib("time", std);
        registerLib("lib/time", std);
        registerLib("lib/all", std);

        // --- 2. <math> (Mathematical functions & Constants) ---
        Map<String, SymbolInfo> math = new LinkedHashMap<>();
        addFunc(math, "abs", 1, "math");
        addFunc(math, "min", 2, "math");
        addFunc(math, "max", 2, "math");
        addFunc(math, "clamp", 3, "math");
        addFunc(math, "sqrt", 1, "math");
        addFunc(math, "rand", 1, "math");
        addConst(math, "PI", 3, "math");
        addConst(math, "E", 2, "math");

        registerLib("math", math);
        registerLib("lib/math", math);

        // --- 3. <Glass> (Formerly lib/graphics: Framebuffer display canvas) ---
        Map<String, SymbolInfo> glass = new LinkedHashMap<>();
        addFunc(glass, "fb_mode", 1, "Glass");
        addFunc(glass, "set_mode", 1, "Glass");
        addFunc(glass, "fb_clear", 1, "Glass");
        addFunc(glass, "clear_screen", 1, "Glass");
        addFunc(glass, "fb_rect", 5, "Glass");
        addFunc(glass, "draw_rect", 5, "Glass");
        addFunc(glass, "fb_line", 5, "Glass");
        addFunc(glass, "draw_line", 5, "Glass");
        addFunc(glass, "fb_pixel", 3, "Glass");
        addFunc(glass, "set_pixel", 3, "Glass");
        addFunc(glass, "fb_get", 2, "Glass");
        addFunc(glass, "get_pixel", 2, "Glass");
        addFunc(glass, "fb_blit", 5, "Glass");
        addFunc(glass, "blit", 5, "Glass");
        addFunc(glass, "fb_swap", 0, "Glass");
        addFunc(glass, "swap", 0, "Glass");
        addFunc(glass, "flip", 0, "Glass");
        addFunc(glass, "fb_width", 0, "Glass");
        addFunc(glass, "width", 0, "Glass");
        addFunc(glass, "fb_height", 0, "Glass");
        addFunc(glass, "height", 0, "Glass");

        addConst(glass, "COLOR_BLACK", 0xFF000000, "Glass");
        addConst(glass, "COLOR_WHITE", 0xFFFFFFFF, "Glass");
        addConst(glass, "COLOR_RED", 0xFFFF0000, "Glass");
        addConst(glass, "COLOR_GREEN", 0xFF00FF00, "Glass");
        addConst(glass, "COLOR_BLUE", 0xFF0000FF, "Glass");
        addConst(glass, "COLOR_YELLOW", 0xFFFFFF00, "Glass");
        addConst(glass, "COLOR_CYAN", 0xFF00FFFF, "Glass");
        addConst(glass, "COLOR_MAGENTA", 0xFFFF00FF, "Glass");
        addConst(glass, "COLOR_TRANSPARENT", 0x00000000, "Glass");

        registerLib("Glass", glass);
        registerLib("glass", glass);
        registerLib("lib/glass", glass);
        registerLib("graphics", glass);
        registerLib("lib/graphics", glass);

        // --- 4. <AudioKit> (Formerly lib/audio: Hardware sound synthesis & PCM buffer) ---
        Map<String, SymbolInfo> audio = new LinkedHashMap<>();
        addFunc(audio, "tone", 2, "AudioKit");
        addFunc(audio, "play_tone", 2, "AudioKit");
        addFunc(audio, "play_audio", 0, "AudioKit");
        addFunc(audio, "audio_play", 0, "AudioKit");
        addFunc(audio, "audio_clear", 0, "AudioKit");
        addFunc(audio, "audio_status", 0, "AudioKit");
        addFunc(audio, "audio_push", 1, "AudioKit");

        registerLib("AudioKit", audio);
        registerLib("audiokit", audio);
        registerLib("lib/audiokit", audio);
        registerLib("audio", audio);
        registerLib("lib/audio", audio);

        // --- 5. <DeviceKit> (Formerly lib/storage, plus keyboard handling from lib/input) ---
        Map<String, SymbolInfo> deviceKit = new LinkedHashMap<>();
        // Multi-drive binary storage
        addFunc(deviceKit, "select_drive", 1, "DeviceKit");
        addFunc(deviceKit, "drive_select", 1, "DeviceKit");
        addFunc(deviceKit, "storage_size", 1, "DeviceKit");
        addFunc(deviceKit, "drive_size", 1, "DeviceKit");
        addFunc(deviceKit, "storage_read", 2, "DeviceKit");
        addFunc(deviceKit, "drive_read", 2, "DeviceKit");
        addFunc(deviceKit, "storage_write", 3, "DeviceKit");
        addFunc(deviceKit, "drive_write", 3, "DeviceKit");
        addFunc(deviceKit, "drive_count", 0, "DeviceKit");

        // Keyboard handling (DeviceKit)
        addFunc(deviceKit, "read_key", 0, "DeviceKit");
        addFunc(deviceKit, "get_key", 0, "DeviceKit");
        addFunc(deviceKit, "key_available", 0, "DeviceKit");
        addFunc(deviceKit, "has_key", 0, "DeviceKit");
        addFunc(deviceKit, "peek_key", 0, "DeviceKit");
        addFunc(deviceKit, "on_key", 1, "DeviceKit");

        // Key Constants
        addConst(deviceKit, "KEY_ENTER", 10, "DeviceKit");
        addConst(deviceKit, "KEY_SPACE", 32, "DeviceKit");
        addConst(deviceKit, "KEY_BACKSPACE", 8, "DeviceKit");
        addConst(deviceKit, "KEY_TAB", 9, "DeviceKit");
        addConst(deviceKit, "KEY_ESCAPE", 27, "DeviceKit");
        addConst(deviceKit, "KEY_UP", 257, "DeviceKit");
        addConst(deviceKit, "KEY_DOWN", 258, "DeviceKit");
        addConst(deviceKit, "KEY_LEFT", 259, "DeviceKit");
        addConst(deviceKit, "KEY_RIGHT", 260, "DeviceKit");

        registerLib("DeviceKit", deviceKit);
        registerLib("devicekit", deviceKit);
        registerLib("lib/devicekit", deviceKit);
        registerLib("storage", deviceKit);
        registerLib("lib/storage", deviceKit);
        registerLib("input", deviceKit);
        registerLib("lib/input", deviceKit);

        // --- 6. <GameController> (Apple Game Controller framework-like API) ---
        Map<String, SymbolInfo> gameCtrl = new LinkedHashMap<>();
        addFunc(gameCtrl, "read_gamepad", 1, "GameController");
        addFunc(gameCtrl, "poll_gamepad", 1, "GameController");
        addFunc(gameCtrl, "get_pad", 1, "GameController");
        addFunc(gameCtrl, "is_gamepad_connected", 1, "GameController");
        addFunc(gameCtrl, "gamepad_count", 0, "GameController");

        // Apple GameController API helpers
        addFunc(gameCtrl, "gc_controllers", 0, "GameController");
        addFunc(gameCtrl, "gc_current", 0, "GameController");
        addFunc(gameCtrl, "gc_poll", 1, "GameController");
        addFunc(gameCtrl, "gc_button_a", 1, "GameController");
        addFunc(gameCtrl, "gc_button_b", 1, "GameController");
        addFunc(gameCtrl, "gc_button_x", 1, "GameController");
        addFunc(gameCtrl, "gc_button_y", 1, "GameController");
        addFunc(gameCtrl, "gc_button_south", 1, "GameController");
        addFunc(gameCtrl, "gc_button_east", 1, "GameController");
        addFunc(gameCtrl, "gc_button_west", 1, "GameController");
        addFunc(gameCtrl, "gc_button_north", 1, "GameController");
        addFunc(gameCtrl, "gc_dpad_up", 1, "GameController");
        addFunc(gameCtrl, "gc_dpad_down", 1, "GameController");
        addFunc(gameCtrl, "gc_dpad_left", 1, "GameController");
        addFunc(gameCtrl, "gc_dpad_right", 1, "GameController");
        addFunc(gameCtrl, "gc_left_shoulder", 1, "GameController");
        addFunc(gameCtrl, "gc_right_shoulder", 1, "GameController");
        addFunc(gameCtrl, "gc_button_menu", 1, "GameController");
        addFunc(gameCtrl, "gc_button_options", 1, "GameController");

        // Apple GameController Button & Element Constants
        addConst(gameCtrl, "GC_BUTTON_A", 0x0080, "GameController");
        addConst(gameCtrl, "GC_BUTTON_B", 0x0040, "GameController");
        addConst(gameCtrl, "GC_BUTTON_X", 0x0200, "GameController");
        addConst(gameCtrl, "GC_BUTTON_Y", 0x0100, "GameController");
        addConst(gameCtrl, "GC_BUTTON_SOUTH", 0x0040, "GameController");
        addConst(gameCtrl, "GC_BUTTON_EAST", 0x0080, "GameController");
        addConst(gameCtrl, "GC_BUTTON_WEST", 0x0100, "GameController");
        addConst(gameCtrl, "GC_BUTTON_NORTH", 0x0200, "GameController");
        addConst(gameCtrl, "GC_DPAD_UP", 0x0008, "GameController");
        addConst(gameCtrl, "GC_DPAD_DOWN", 0x0004, "GameController");
        addConst(gameCtrl, "GC_DPAD_LEFT", 0x0002, "GameController");
        addConst(gameCtrl, "GC_DPAD_RIGHT", 0x0001, "GameController");
        addConst(gameCtrl, "GC_LEFT_SHOULDER", 0x0400, "GameController");
        addConst(gameCtrl, "GC_RIGHT_SHOULDER", 0x0800, "GameController");
        addConst(gameCtrl, "GC_BUTTON_MENU", 0x0010, "GameController");
        addConst(gameCtrl, "GC_BUTTON_OPTIONS", 0x0020, "GameController");

        // Retro / SNES aliases
        addConst(gameCtrl, "PAD_RIGHT", 1 << 0, "GameController");
        addConst(gameCtrl, "PAD_LEFT", 1 << 1, "GameController");
        addConst(gameCtrl, "PAD_DOWN", 1 << 2, "GameController");
        addConst(gameCtrl, "PAD_UP", 1 << 3, "GameController");
        addConst(gameCtrl, "PAD_START", 1 << 4, "GameController");
        addConst(gameCtrl, "PAD_SELECT", 1 << 5, "GameController");
        addConst(gameCtrl, "PAD_B", 1 << 6, "GameController");
        addConst(gameCtrl, "PAD_A", 1 << 7, "GameController");
        addConst(gameCtrl, "PAD_Y", 1 << 8, "GameController");
        addConst(gameCtrl, "PAD_X", 1 << 9, "GameController");
        addConst(gameCtrl, "PAD_L", 1 << 10, "GameController");
        addConst(gameCtrl, "PAD_R", 1 << 11, "GameController");

        registerLib("GameController", gameCtrl);
        registerLib("gamecontroller", gameCtrl);
        registerLib("lib/gamecontroller", gameCtrl);
        registerLib("gamepad", gameCtrl);
        registerLib("lib/gamepad", gameCtrl);

        // Index all canonical symbols for helpful error suggestions
        for (Map.Entry<String, Map<String, SymbolInfo>> libEntry : LIBRARIES.entrySet()) {
            for (String sym : libEntry.getValue().keySet()) {
                SYMBOL_TO_LIB.putIfAbsent(sym, libEntry.getValue().get(sym).library());
            }
        }
    }

    private static void registerLib(String name, Map<String, SymbolInfo> map) {
        LIBRARIES.put(name, map);
    }

    private static void addFunc(Map<String, SymbolInfo> map, String name, int arity, String lib) {
        map.put(name, new SymbolInfo(name, SymbolType.FUNCTION, null, arity, lib));
    }

    private static void addConst(Map<String, SymbolInfo> map, String name, int value, String lib) {
        map.put(name, new SymbolInfo(name, SymbolType.CONSTANT, value, 0, lib));
    }

    public static boolean isLibrary(String path) {
        return findLibMap(path) != null;
    }

    public static Map<String, SymbolInfo> getLibrary(String path) {
        return findLibMap(path);
    }

    private static Map<String, SymbolInfo> findLibMap(String rawPath) {
        String clean = normalizeLibPath(rawPath);
        if (LIBRARIES.containsKey(clean)) {
            return LIBRARIES.get(clean);
        }
        String lower = clean.toLowerCase(Locale.ROOT);
        if (LIBRARIES.containsKey(lower)) {
            return LIBRARIES.get(lower);
        }
        if (clean.startsWith("lib/")) {
            String without = clean.substring(4);
            if (LIBRARIES.containsKey(without)) return LIBRARIES.get(without);
            if (LIBRARIES.containsKey(without.toLowerCase(Locale.ROOT))) return LIBRARIES.get(without.toLowerCase(Locale.ROOT));
        } else {
            String withLib = "lib/" + clean;
            if (LIBRARIES.containsKey(withLib)) return LIBRARIES.get(withLib);
            if (LIBRARIES.containsKey(withLib.toLowerCase(Locale.ROOT))) return LIBRARIES.get(withLib.toLowerCase(Locale.ROOT));
        }
        return null;
    }

    public static String getSuggestedLibraryForSymbol(String symbol) {
        return SYMBOL_TO_LIB.get(symbol);
    }

    public static String normalizeLibPath(String path) {
        if (path == null) return "";
        String clean = path.trim();
        if (clean.startsWith("<") && clean.endsWith(">")) {
            clean = clean.substring(1, clean.length() - 1).trim();
        }
        return clean;
    }
}
