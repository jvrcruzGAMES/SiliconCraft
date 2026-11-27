package games.jvrcruz.siliconcraft.compiler.n2;

import games.jvrcruz.siliconcraft.vm.PeripheralBus;
import games.jvrcruz.siliconcraft.vm.network.NetworkPort;

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
        addFunc(std, "pcall", 1, "std");
        addFunc(std, "error", 1, "std");

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

        // Graphics processor selection & GPU telemetry / acceleration
        addFunc(glass, "gfx_set_handler", 1, "Glass");
        addFunc(glass, "use_auto_gfx", 0, "Glass");
        addFunc(glass, "use_cpu", 0, "Glass");
        addFunc(glass, "use_gpu", 0, "Glass");
        addFunc(glass, "has_gpu", 0, "Glass");
        addFunc(glass, "gpu_available", 0, "Glass");
        addFunc(glass, "gfx_handler", 0, "Glass");
        addFunc(glass, "is_using_gpu", 0, "Glass");
        addFunc(glass, "gfx_mode", 0, "Glass");
        addFunc(glass, "gpu_vram", 0, "Glass");
        addFunc(glass, "gpu_vram_kb", 0, "Glass");

        addFunc(glass, "gpu_clear", 1, "Glass");
        addFunc(glass, "gpu_rect", 5, "Glass");
        addFunc(glass, "gpu_line", 5, "Glass");
        addFunc(glass, "draw_circle", 4, "Glass");
        addFunc(glass, "gpu_circle", 4, "Glass");
        addFunc(glass, "fill_circle", 4, "Glass");
        addFunc(glass, "gpu_fill_circle", 4, "Glass");
        addFunc(glass, "fill_triangle", 7, "Glass");
        addFunc(glass, "gpu_triangle", 7, "Glass");
        addFunc(glass, "vram_upload", 3, "Glass");
        addFunc(glass, "gpu_blit", 5, "Glass");
        addFunc(glass, "gpu_swap", 0, "Glass");
        addFunc(glass, "gpu_sync", 0, "Glass");

        addConst(glass, "GFX_AUTO", 0, "Glass");
        addConst(glass, "GFX_CPU", 1, "Glass");
        addConst(glass, "GFX_GPU", 2, "Glass");

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

        // Adjacent block APIs
        addFunc(deviceKit, "peripheral_scan", 1, "DeviceKit");
        addFunc(deviceKit, "peripheral_info", 1, "DeviceKit");
        addFunc(deviceKit, "peripheral_wrap", 1, "DeviceKit");
        addFunc(deviceKit, "peripheral_call", 4, "DeviceKit");
        addFunc(deviceKit, "peripheral_call_string", 4, "DeviceKit");
        addFunc(deviceKit, "peripheral_status", 0, "DeviceKit");
        addFunc(deviceKit, "peripheral_result", 0, "DeviceKit");
        addFunc(deviceKit, "speaker_sample_rate", 1, "DeviceKit");
        addFunc(deviceKit, "speaker_tone", 3, "DeviceKit");
        addFunc(deviceKit, "note_block_get", 1, "DeviceKit");
        addFunc(deviceKit, "note_block_set", 2, "DeviceKit");
        addFunc(deviceKit, "note_block_play", 2, "DeviceKit");
        addFunc(deviceKit, "note_block_play_instrument", 3, "DeviceKit");

        addFunc(deviceKit, "peripheral_length", 0, "DeviceKit");
        addFunc(deviceKit, "peripheral_result_text", 2, "DeviceKit");
        addFunc(deviceKit, "peripheral_text", 0, "DeviceKit");
        addFunc(deviceKit, "peripheral_text_unit", 2, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_CHAT_SEND", 0x400, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_CHAT_SEND_TO", 0x401, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_CHAT_POLL", 0x402, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_CHAT_FORMATTED", 0x403, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_CHAT_TOAST", 0x404, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_CHAT_NARRATE", 0x405, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_DISTANCE", 0x410, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_DISTANCE_MODE", 0x411, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_DISTANCE_SET_MODE", 0x412, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_DISTANCE_RANGE", 0x413, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_DISTANCE_SET_RANGE", 0x414, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_DISTANCE_TRANSPARENT", 0x415, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_DISTANCE_SET_TRANSPARENT", 0x416, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_DISTANCE_LASER", 0x417, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_DISTANCE_SET_LASER", 0x418, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_DISTANCE_PERIODIC", 0x419, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_DISTANCE_SET_PERIODIC", 0x41a, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_DISTANCE_CACHED", 0x41b, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_ENV_BIOME", 0x420, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_ENV_DIMENSION", 0x421, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_ENV_TIME", 0x422, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_ENV_MOON", 0x423, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_ENV_BLOCK_LIGHT", 0x424, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_ENV_SKY_LIGHT", 0x425, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_ENV_DAY_LIGHT", 0x426, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_ENV_WEATHER", 0x427, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_ENV_SLIME", 0x428, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_ENV_DIMENSIONS", 0x429, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_ENV_ENTITIES", 0x42a, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_ENV_RADIATION", 0x42b, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_ENV_STRUCTURES", 0x42c, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_ENV_POSITION", 0x42d, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_COMPASS_LOCATE", 0x490, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_PLAYER_ONLINE", 0x430, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_PLAYER_RANGE", 0x431, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_PLAYER_INFO", 0x432, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_PLAYER_AREA", 0x433, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_PLAYER_EVENTS", 0x434, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_PLAYER_STAT", 0x435, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_PLAYER_OWNER", 0x436, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_PLAYER_CUBE", 0x437, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_BLOCK_NAME", 0x440, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_BLOCK_STATE", 0x441, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_BLOCK_DATA", 0x442, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_BLOCK_HAS_ENTITY", 0x443, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_BLOCK_EVENTS", 0x444, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_INVENTORY_OWNER", 0x450, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_INVENTORY_SIZE", 0x451, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_INVENTORY_SLOT", 0x452, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_INVENTORY_FREE", 0x453, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_INVENTORY_EMPTY", 0x454, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_INVENTORY_HAND", 0x455, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_INVENTORY_OFFHAND", 0x456, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_INVENTORY_IMPORT", 0x457, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_INVENTORY_EXPORT", 0x458, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_INVENTORY_LIST", 0x459, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_INVENTORY_ARMOR", 0x45a, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_INVENTORY_HAND_SLOT", 0x45b, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_INVENTORY_EMPTY_SLOTS", 0x45c, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_INVENTORY_IS_WEARING", 0x45d, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_INVENTORY_CURIO_SIZES", 0x45e, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_INVENTORY_CURIO_LIST", 0x45f, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_INVENTORY_CURIO_IMPORT", 0x480, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_INVENTORY_CURIO_EXPORT", 0x481, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_INVENTORY_STORAGE_INFO", 0x482, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_INVENTORY_STORAGE_IMPORT", 0x483, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_INVENTORY_STORAGE_EXPORT", 0x484, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_INVENTORY_FLUID_IMPORT", 0x485, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_INVENTORY_FLUID_EXPORT", 0x486, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_INVENTORY_ITEM_DATA", 0x487, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_REDSTONE_INPUT", 0x460, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_REDSTONE_OUTPUT", 0x461, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_REDSTONE_SET_OUTPUT", 0x462, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_FLOW_RATE", 0x470, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_FLOW_LIMIT", 0x471, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_FLOW_SET_LIMIT", 0x472, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_FLOW_MAX", 0x473, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_FLOW_LAST_ID", 0x474, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_FLOW_READY_ID", 0x475, "DeviceKit");

        addConst(deviceKit, "PERIPHERAL_DOWN", 0, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_UP", 1, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_NORTH", 2, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_SOUTH", 3, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_WEST", 4, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_EAST", 5, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_IDLE", PeripheralBus.IDLE, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_PENDING", PeripheralBus.PENDING, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_OK", PeripheralBus.OK, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_MISSING", PeripheralBus.MISSING, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_UNWRAPPED", PeripheralBus.UNWRAPPED, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_UNSUPPORTED", PeripheralBus.UNSUPPORTED, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_FAILED", PeripheralBus.FAILED, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_PRESENT", PeripheralBus.PRESENT, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_WRAPPED", PeripheralBus.WRAPPED, "DeviceKit");
        addConst(deviceKit, "PERIPHERAL_AUTOMATIC", PeripheralBus.AUTOMATIC, "DeviceKit");

        registerLib("DeviceKit", deviceKit);
        registerLib("devicekit", deviceKit);
        registerLib("lib/devicekit", deviceKit);
        registerLib("storage", deviceKit);
        registerLib("lib/storage", deviceKit);
        registerLib("input", deviceKit);
        registerLib("lib/input", deviceKit);

        Map<String, SymbolInfo> network = new LinkedHashMap<>();
        addFunc(network, "network_open", 3, "Network");
        addFunc(network, "network_send", 3, "Network");
        addFunc(network, "network_receive", 2, "Network");
        addFunc(network, "network_close", 1, "Network");
        addFunc(network, "network_http", 4, "Network");
        addFunc(network, "network_http3", 4, "Network");
        addFunc(network, "network_http_text", 4, "Network");
        addFunc(network, "network_http3_text", 4, "Network");
        addFunc(network, "network_listen", 2, "Network");
        addFunc(network, "network_accept", 1, "Network");
        addFunc(network, "network_respond", 4, "Network");
        addFunc(network, "network_websocket", 1, "Network");
        addFunc(network, "network_send_text", 3, "Network");
        addFunc(network, "network_tls", 1, "Network");
        addFunc(network, "network_status", 0, "Network");
        addFunc(network, "network_length", 0, "Network");
        addFunc(network, "network_result", 2, "Network");
        addConst(network, "NET_TCP", NetworkPort.TCP, "Network");
        addConst(network, "NET_UDP", NetworkPort.UDP, "Network");
        addConst(network, "NET_SMTP", NetworkPort.SMTP, "Network");
        addConst(network, "NET_SMTPS", NetworkPort.SMTPS, "Network");
        addConst(network, "NET_IMAP", NetworkPort.IMAP, "Network");
        addConst(network, "NET_IMAPS", NetworkPort.IMAPS, "Network");
        addConst(network, "NET_HTTP", NetworkPort.HTTP, "Network");
        addConst(network, "NET_HTTPS", NetworkPort.HTTPS, "Network");
        addConst(network, "NET_WS", NetworkPort.WS, "Network");
        addConst(network, "NET_WSS", NetworkPort.WSS, "Network");
        addConst(network, "NET_GET", 0, "Network");
        addConst(network, "NET_POST", 1, "Network");
        addConst(network, "NET_PUT", 2, "Network");
        addConst(network, "NET_DELETE", 3, "Network");
        addConst(network, "NET_PATCH", 4, "Network");
        addConst(network, "NET_HEAD", 5, "Network");
        addConst(network, "NET_IDLE", NetworkPort.IDLE, "Network");
        addConst(network, "NET_PENDING", NetworkPort.PENDING, "Network");
        addConst(network, "NET_OK", NetworkPort.OK, "Network");
        addConst(network, "NET_DISABLED", NetworkPort.DISABLED, "Network");
        addConst(network, "NET_DENIED", NetworkPort.DENIED, "Network");
        addConst(network, "NET_IO_ERROR", NetworkPort.IO_ERROR, "Network");
        addConst(network, "NET_LIMIT", NetworkPort.LIMIT, "Network");
        addConst(network, "NET_INVALID", NetworkPort.INVALID, "Network");
        addConst(network, "NET_TIMEOUT", NetworkPort.TIMEOUT, "Network");
        addFunc(network, "network_respond_text", 4, "Network");
        addFunc(network, "network_ready", 0, "Network");
        addFunc(network, "network_utf8", 4, "Network");
        addConst(network, "NET_BUSY", -7, "Network");
        addConst(network, "NET_ANY", -1, "Network");
        for (var api : N2NetworkRuntime.APIS.entrySet()) {
            addFunc(network, api.getKey(), api.getValue().arity(), "Network");
        }
        registerLib("Network", network);
        registerLib("lib/network", network);

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
        addFunc(gameCtrl, "gc_axis", 2, "GameController");
        addFunc(gameCtrl, "gc_left_stick_x", 1, "GameController");
        addFunc(gameCtrl, "gc_left_stick_y", 1, "GameController");
        addFunc(gameCtrl, "gc_right_stick_x", 1, "GameController");
        addFunc(gameCtrl, "gc_right_stick_y", 1, "GameController");
        addFunc(gameCtrl, "gc_left_trigger", 1, "GameController");
        addFunc(gameCtrl, "gc_right_trigger", 1, "GameController");
        addFunc(gameCtrl, "gc_left_trigger_pressed", 1, "GameController");
        addFunc(gameCtrl, "gc_right_trigger_pressed", 1, "GameController");
        addFunc(gameCtrl, "gc_left_stick_button", 1, "GameController");
        addFunc(gameCtrl, "gc_right_stick_button", 1, "GameController");

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
        addConst(gameCtrl, "GC_LEFT_TRIGGER", 0x1000, "GameController");
        addConst(gameCtrl, "GC_RIGHT_TRIGGER", 0x2000, "GameController");
        addConst(gameCtrl, "GC_LEFT_STICK_BUTTON", 0x4000, "GameController");
        addConst(gameCtrl, "GC_RIGHT_STICK_BUTTON", 0x8000, "GameController");
        addConst(gameCtrl, "GC_AXIS_LEFT_X", 0, "GameController");
        addConst(gameCtrl, "GC_AXIS_LEFT_Y", 1, "GameController");
        addConst(gameCtrl, "GC_AXIS_RIGHT_X", 2, "GameController");
        addConst(gameCtrl, "GC_AXIS_RIGHT_Y", 3, "GameController");
        addConst(gameCtrl, "GC_AXIS_LEFT_TRIGGER", 4, "GameController");
        addConst(gameCtrl, "GC_AXIS_RIGHT_TRIGGER", 5, "GameController");

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
        addConst(gameCtrl, "PAD_LEFT_TRIGGER", 1 << 12, "GameController");
        addConst(gameCtrl, "PAD_RIGHT_TRIGGER", 1 << 13, "GameController");
        addConst(gameCtrl, "PAD_LEFT_STICK", 1 << 14, "GameController");
        addConst(gameCtrl, "PAD_RIGHT_STICK", 1 << 15, "GameController");

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
