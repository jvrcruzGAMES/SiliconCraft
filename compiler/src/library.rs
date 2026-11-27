use std::collections::HashMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SymbolType {
    Function,
    Constant,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SymbolInfo {
    pub name: String,
    pub kind: SymbolType,
    pub int_value: Option<i32>,
    pub arity: usize,
    pub library: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct NetworkApi {
    pub arity: usize,
    pub callback_arity: Option<usize>,
}

pub fn network_apis() -> HashMap<&'static str, NetworkApi> {
    HashMap::from([
        ("http_get", NetworkApi { arity: 2, callback_arity: Some(4) }),
        ("http_post", NetworkApi { arity: 3, callback_arity: Some(4) }),
        ("http_request", NetworkApi { arity: 4, callback_arity: Some(4) }),
        ("http_request_bytes", NetworkApi { arity: 5, callback_arity: Some(4) }),
        ("http3_request", NetworkApi { arity: 4, callback_arity: Some(4) }),
        ("http3_request_bytes", NetworkApi { arity: 5, callback_arity: Some(4) }),
        ("http_text", NetworkApi { arity: 2, callback_arity: None }),
        ("http_body", NetworkApi { arity: 2, callback_arity: None }),
        ("http_reply", NetworkApi { arity: 2, callback_arity: None }),
        ("http_reply_bytes", NetworkApi { arity: 3, callback_arity: None }),
        ("http_route", NetworkApi { arity: 3, callback_arity: Some(2) }),
        ("http_listen", NetworkApi { arity: 2, callback_arity: None }),
        ("http_dispatch", NetworkApi { arity: 2, callback_arity: None }),
        ("http_serve", NetworkApi { arity: 2, callback_arity: None }),
    ])
}

pub fn normalize_lib_path(path: &str) -> String {
    let mut clean = path.trim();
    if clean.starts_with('<') && clean.ends_with('>') && clean.len() >= 2 {
        clean = clean[1..clean.len() - 1].trim();
    }
    clean.to_string()
}

fn add_func(map: &mut HashMap<String, SymbolInfo>, name: &str, arity: usize, lib: &str) {
    map.insert(
        name.to_string(),
        SymbolInfo {
            name: name.to_string(),
            kind: SymbolType::Function,
            int_value: None,
            arity,
            library: lib.to_string(),
        },
    );
}

fn add_const(map: &mut HashMap<String, SymbolInfo>, name: &str, value: i32, lib: &str) {
    map.insert(
        name.to_string(),
        SymbolInfo {
            name: name.to_string(),
            kind: SymbolType::Constant,
            int_value: Some(value),
            arity: 0,
            library: lib.to_string(),
        },
    );
}

fn build_libraries() -> (HashMap<String, HashMap<String, SymbolInfo>>, HashMap<String, String>) {
    let mut libraries: HashMap<String, HashMap<String, SymbolInfo>> = HashMap::new();
    let mut symbol_to_lib: HashMap<String, String> = HashMap::new();

    // 1. <std>
    let mut std_lib = HashMap::new();
    for (name, arity) in [
        ("print", 1),
        ("println", 1),
        ("write", 1),
        ("cls", 0),
        ("out", 2),
        ("read", 0),
        ("time", 0),
        ("sleep", 1),
        ("set_timer", 1),
        ("abs", 1),
        ("min", 2),
        ("max", 2),
        ("clamp", 3),
        ("assert", 1),
        ("pcall", 1),
        ("error", 1),
    ] {
        add_func(&mut std_lib, name, arity, "std");
    }
    for alias in ["std", "lib/std", "io", "lib/io", "time", "lib/time", "lib/all"] {
        libraries.insert(alias.to_string(), std_lib.clone());
    }

    // 2. <math>
    let mut math_lib = HashMap::new();
    for (name, arity) in [
        ("abs", 1),
        ("min", 2),
        ("max", 2),
        ("clamp", 3),
        ("sqrt", 1),
        ("rand", 1),
    ] {
        add_func(&mut math_lib, name, arity, "math");
    }
    add_const(&mut math_lib, "PI", 3, "math");
    add_const(&mut math_lib, "E", 2, "math");
    for alias in ["math", "lib/math"] {
        libraries.insert(alias.to_string(), math_lib.clone());
    }

    // 3. <Glass>
    let mut glass = HashMap::new();
    for (name, arity) in [
        ("fb_mode", 1),
        ("set_mode", 1),
        ("fb_clear", 1),
        ("clear_screen", 1),
        ("fb_rect", 5),
        ("draw_rect", 5),
        ("fb_line", 5),
        ("draw_line", 5),
        ("fb_pixel", 3),
        ("set_pixel", 3),
        ("fb_get", 2),
        ("get_pixel", 2),
        ("fb_blit", 5),
        ("blit", 5),
        ("fb_swap", 0),
        ("swap", 0),
        ("flip", 0),
        ("fb_width", 0),
        ("width", 0),
        ("fb_height", 0),
        ("height", 0),
        ("gfx_set_handler", 1),
        ("use_auto_gfx", 0),
        ("use_cpu", 0),
        ("use_gpu", 0),
        ("has_gpu", 0),
        ("gpu_available", 0),
        ("gfx_handler", 0),
        ("is_using_gpu", 0),
        ("gfx_mode", 0),
        ("gpu_vram", 0),
        ("gpu_vram_kb", 0),
        ("gpu_clear", 1),
        ("gpu_rect", 5),
        ("gpu_line", 5),
        ("draw_circle", 4),
        ("gpu_circle", 4),
        ("fill_circle", 4),
        ("gpu_fill_circle", 4),
        ("fill_triangle", 7),
        ("gpu_triangle", 7),
        ("vram_upload", 3),
        ("gpu_blit", 5),
        ("gpu_swap", 0),
        ("gpu_sync", 0),
    ] {
        add_func(&mut glass, name, arity, "Glass");
    }
    add_const(&mut glass, "GFX_AUTO", 0, "Glass");
    add_const(&mut glass, "GFX_CPU", 1, "Glass");
    add_const(&mut glass, "GFX_GPU", 2, "Glass");
    add_const(&mut glass, "COLOR_BLACK", 0xFF000000u32 as i32, "Glass");
    add_const(&mut glass, "COLOR_WHITE", 0xFFFFFFFFu32 as i32, "Glass");
    add_const(&mut glass, "COLOR_RED", 0xFFFF0000u32 as i32, "Glass");
    add_const(&mut glass, "COLOR_GREEN", 0xFF00FF00u32 as i32, "Glass");
    add_const(&mut glass, "COLOR_BLUE", 0xFF0000FFu32 as i32, "Glass");
    add_const(&mut glass, "COLOR_YELLOW", 0xFFFFFF00u32 as i32, "Glass");
    add_const(&mut glass, "COLOR_CYAN", 0xFF00FFFFu32 as i32, "Glass");
    add_const(&mut glass, "COLOR_MAGENTA", 0xFFFF00FFu32 as i32, "Glass");
    add_const(&mut glass, "COLOR_TRANSPARENT", 0x00000000, "Glass");
    for alias in ["Glass", "glass", "lib/glass", "graphics", "lib/graphics"] {
        libraries.insert(alias.to_string(), glass.clone());
    }

    // 4. <AudioKit>
    let mut audio = HashMap::new();
    for (name, arity) in [
        ("tone", 2),
        ("play_tone", 2),
        ("play_audio", 0),
        ("audio_play", 0),
        ("audio_clear", 0),
        ("audio_status", 0),
        ("audio_push", 1),
    ] {
        add_func(&mut audio, name, arity, "AudioKit");
    }
    for alias in ["AudioKit", "audiokit", "lib/audiokit", "audio", "lib/audio"] {
        libraries.insert(alias.to_string(), audio.clone());
    }

    // 5. <DeviceKit>
    let mut device = HashMap::new();
    for (name, arity) in [
        ("select_drive", 1),
        ("drive_select", 1),
        ("storage_size", 1),
        ("drive_size", 1),
        ("storage_read", 2),
        ("drive_read", 2),
        ("storage_write", 3),
        ("drive_write", 3),
        ("drive_count", 0),
        ("read_key", 0),
        ("get_key", 0),
        ("key_available", 0),
        ("has_key", 0),
        ("peek_key", 0),
        ("on_key", 1),
        ("peripheral_scan", 1),
        ("peripheral_info", 1),
        ("peripheral_wrap", 1),
        ("peripheral_call", 4),
        ("peripheral_call_string", 4),
        ("peripheral_status", 0),
        ("peripheral_result", 0),
        ("speaker_sample_rate", 1),
        ("speaker_tone", 3),
        ("note_block_get", 1),
        ("note_block_set", 2),
        ("note_block_play", 2),
        ("note_block_play_instrument", 3),
        ("peripheral_length", 0),
        ("peripheral_result_text", 2),
        ("peripheral_text", 0),
        ("peripheral_text_unit", 2),
    ] {
        add_func(&mut device, name, arity, "DeviceKit");
    }
    for (name, val) in [
        ("KEY_ENTER", 10),
        ("KEY_SPACE", 32),
        ("KEY_BACKSPACE", 8),
        ("KEY_TAB", 9),
        ("KEY_ESCAPE", 27),
        ("KEY_UP", 257),
        ("KEY_DOWN", 258),
        ("KEY_LEFT", 259),
        ("KEY_RIGHT", 260),
        ("PERIPHERAL_CHAT_SEND", 0x400),
        ("PERIPHERAL_CHAT_SEND_TO", 0x401),
        ("PERIPHERAL_CHAT_POLL", 0x402),
        ("PERIPHERAL_CHAT_FORMATTED", 0x403),
        ("PERIPHERAL_CHAT_TOAST", 0x404),
        ("PERIPHERAL_CHAT_NARRATE", 0x405),
        ("PERIPHERAL_DISTANCE", 0x410),
        ("PERIPHERAL_DISTANCE_MODE", 0x411),
        ("PERIPHERAL_DISTANCE_SET_MODE", 0x412),
        ("PERIPHERAL_DISTANCE_RANGE", 0x413),
        ("PERIPHERAL_DISTANCE_SET_RANGE", 0x414),
        ("PERIPHERAL_DISTANCE_TRANSPARENT", 0x415),
        ("PERIPHERAL_DISTANCE_SET_TRANSPARENT", 0x416),
        ("PERIPHERAL_DISTANCE_LASER", 0x417),
        ("PERIPHERAL_DISTANCE_SET_LASER", 0x418),
        ("PERIPHERAL_DISTANCE_PERIODIC", 0x419),
        ("PERIPHERAL_DISTANCE_SET_PERIODIC", 0x41a),
        ("PERIPHERAL_DISTANCE_CACHED", 0x41b),
        ("PERIPHERAL_ENV_BIOME", 0x420),
        ("PERIPHERAL_ENV_DIMENSION", 0x421),
        ("PERIPHERAL_ENV_TIME", 0x422),
        ("PERIPHERAL_ENV_MOON", 0x423),
        ("PERIPHERAL_ENV_BLOCK_LIGHT", 0x424),
        ("PERIPHERAL_ENV_SKY_LIGHT", 0x425),
        ("PERIPHERAL_ENV_DAY_LIGHT", 0x426),
        ("PERIPHERAL_ENV_WEATHER", 0x427),
        ("PERIPHERAL_ENV_SLIME", 0x428),
        ("PERIPHERAL_ENV_DIMENSIONS", 0x429),
        ("PERIPHERAL_ENV_ENTITIES", 0x42a),
        ("PERIPHERAL_ENV_RADIATION", 0x42b),
        ("PERIPHERAL_ENV_STRUCTURES", 0x42c),
        ("PERIPHERAL_ENV_POSITION", 0x42d),
        ("PERIPHERAL_COMPASS_LOCATE", 0x490),
        ("PERIPHERAL_PLAYER_ONLINE", 0x430),
        ("PERIPHERAL_PLAYER_RANGE", 0x431),
        ("PERIPHERAL_PLAYER_INFO", 0x432),
        ("PERIPHERAL_PLAYER_AREA", 0x433),
        ("PERIPHERAL_PLAYER_EVENTS", 0x434),
        ("PERIPHERAL_PLAYER_STAT", 0x435),
        ("PERIPHERAL_PLAYER_OWNER", 0x436),
        ("PERIPHERAL_PLAYER_CUBE", 0x437),
        ("PERIPHERAL_BLOCK_NAME", 0x440),
        ("PERIPHERAL_BLOCK_STATE", 0x441),
        ("PERIPHERAL_BLOCK_DATA", 0x442),
        ("PERIPHERAL_BLOCK_HAS_ENTITY", 0x443),
        ("PERIPHERAL_BLOCK_EVENTS", 0x444),
        ("PERIPHERAL_INVENTORY_OWNER", 0x450),
        ("PERIPHERAL_INVENTORY_SIZE", 0x451),
        ("PERIPHERAL_INVENTORY_SLOT", 0x452),
        ("PERIPHERAL_INVENTORY_FREE", 0x453),
        ("PERIPHERAL_INVENTORY_EMPTY", 0x454),
        ("PERIPHERAL_INVENTORY_HAND", 0x455),
        ("PERIPHERAL_INVENTORY_OFFHAND", 0x456),
        ("PERIPHERAL_INVENTORY_IMPORT", 0x457),
        ("PERIPHERAL_INVENTORY_EXPORT", 0x458),
        ("PERIPHERAL_INVENTORY_LIST", 0x459),
        ("PERIPHERAL_INVENTORY_ARMOR", 0x45a),
        ("PERIPHERAL_INVENTORY_HAND_SLOT", 0x45b),
        ("PERIPHERAL_INVENTORY_EMPTY_SLOTS", 0x45c),
        ("PERIPHERAL_INVENTORY_IS_WEARING", 0x45d),
        ("PERIPHERAL_INVENTORY_CURIO_SIZES", 0x45e),
        ("PERIPHERAL_INVENTORY_CURIO_LIST", 0x45f),
        ("PERIPHERAL_INVENTORY_CURIO_IMPORT", 0x480),
        ("PERIPHERAL_INVENTORY_CURIO_EXPORT", 0x481),
        ("PERIPHERAL_INVENTORY_STORAGE_INFO", 0x482),
        ("PERIPHERAL_INVENTORY_STORAGE_IMPORT", 0x483),
        ("PERIPHERAL_INVENTORY_STORAGE_EXPORT", 0x484),
        ("PERIPHERAL_INVENTORY_FLUID_IMPORT", 0x485),
        ("PERIPHERAL_INVENTORY_FLUID_EXPORT", 0x486),
        ("PERIPHERAL_INVENTORY_ITEM_DATA", 0x487),
        ("PERIPHERAL_REDSTONE_INPUT", 0x460),
        ("PERIPHERAL_REDSTONE_OUTPUT", 0x461),
        ("PERIPHERAL_REDSTONE_SET_OUTPUT", 0x462),
        ("PERIPHERAL_FLOW_RATE", 0x470),
        ("PERIPHERAL_FLOW_LIMIT", 0x471),
        ("PERIPHERAL_FLOW_SET_LIMIT", 0x472),
        ("PERIPHERAL_FLOW_MAX", 0x473),
        ("PERIPHERAL_FLOW_LAST_ID", 0x474),
        ("PERIPHERAL_FLOW_READY_ID", 0x475),
        ("PERIPHERAL_DOWN", 0),
        ("PERIPHERAL_UP", 1),
        ("PERIPHERAL_NORTH", 2),
        ("PERIPHERAL_SOUTH", 3),
        ("PERIPHERAL_WEST", 4),
        ("PERIPHERAL_EAST", 5),
        ("PERIPHERAL_IDLE", 0),
        ("PERIPHERAL_PENDING", 1),
        ("PERIPHERAL_OK", 2),
        ("PERIPHERAL_MISSING", -1),
        ("PERIPHERAL_UNWRAPPED", -2),
        ("PERIPHERAL_UNSUPPORTED", -3),
        ("PERIPHERAL_FAILED", -4),
        ("PERIPHERAL_PRESENT", 1),
        ("PERIPHERAL_WRAPPED", 2),
        ("PERIPHERAL_AUTOMATIC", 4),
    ] {
        add_const(&mut device, name, val, "DeviceKit");
    }
    for alias in [
        "DeviceKit",
        "devicekit",
        "lib/devicekit",
        "storage",
        "lib/storage",
        "input",
        "lib/input",
    ] {
        libraries.insert(alias.to_string(), device.clone());
    }

    // 6. <Network>
    let mut network = HashMap::new();
    for (name, arity) in [
        ("network_open", 3),
        ("network_send", 3),
        ("network_receive", 2),
        ("network_close", 1),
        ("network_http", 4),
        ("network_http3", 4),
        ("network_http_text", 4),
        ("network_http3_text", 4),
        ("network_listen", 2),
        ("network_accept", 1),
        ("network_respond", 4),
        ("network_websocket", 1),
        ("network_send_text", 3),
        ("network_tls", 1),
        ("network_status", 0),
        ("network_length", 0),
        ("network_result", 2),
        ("network_respond_text", 4),
        ("network_ready", 0),
        ("network_utf8", 4),
    ] {
        add_func(&mut network, name, arity, "Network");
    }
    for (name, api) in network_apis() {
        add_func(&mut network, name, api.arity, "Network");
    }
    for (name, val) in [
        ("NET_TCP", 1),
        ("NET_UDP", 2),
        ("NET_SMTP", 3),
        ("NET_SMTPS", 4),
        ("NET_IMAP", 5),
        ("NET_IMAPS", 6),
        ("NET_HTTP", 7),
        ("NET_HTTPS", 8),
        ("NET_WS", 9),
        ("NET_WSS", 10),
        ("NET_GET", 0),
        ("NET_POST", 1),
        ("NET_PUT", 2),
        ("NET_DELETE", 3),
        ("NET_PATCH", 4),
        ("NET_HEAD", 5),
        ("NET_IDLE", 0),
        ("NET_PENDING", 1),
        ("NET_OK", 2),
        ("NET_DISABLED", -1),
        ("NET_DENIED", -2),
        ("NET_IO_ERROR", -3),
        ("NET_LIMIT", -4),
        ("NET_INVALID", -5),
        ("NET_TIMEOUT", -6),
        ("NET_BUSY", -7),
        ("NET_ANY", -1),
    ] {
        add_const(&mut network, name, val, "Network");
    }
    for alias in ["Network", "lib/network"] {
        libraries.insert(alias.to_string(), network.clone());
    }

    // 7. <GameController>
    let mut gc = HashMap::new();
    for (name, arity) in [
        ("read_gamepad", 1),
        ("poll_gamepad", 1),
        ("get_pad", 1),
        ("is_gamepad_connected", 1),
        ("gamepad_count", 0),
        ("gc_controllers", 0),
        ("gc_current", 0),
        ("gc_poll", 1),
        ("gc_button_a", 1),
        ("gc_button_b", 1),
        ("gc_button_x", 1),
        ("gc_button_y", 1),
        ("gc_button_south", 1),
        ("gc_button_east", 1),
        ("gc_button_west", 1),
        ("gc_button_north", 1),
        ("gc_dpad_up", 1),
        ("gc_dpad_down", 1),
        ("gc_dpad_left", 1),
        ("gc_dpad_right", 1),
        ("gc_left_shoulder", 1),
        ("gc_right_shoulder", 1),
        ("gc_button_menu", 1),
        ("gc_button_options", 1),
        ("gc_axis", 2),
        ("gc_left_stick_x", 1),
        ("gc_left_stick_y", 1),
        ("gc_right_stick_x", 1),
        ("gc_right_stick_y", 1),
        ("gc_left_trigger", 1),
        ("gc_right_trigger", 1),
        ("gc_left_trigger_pressed", 1),
        ("gc_right_trigger_pressed", 1),
        ("gc_left_stick_button", 1),
        ("gc_right_stick_button", 1),
    ] {
        add_func(&mut gc, name, arity, "GameController");
    }
    for (name, val) in [
        ("GC_BUTTON_A", 0x0080),
        ("GC_BUTTON_B", 0x0040),
        ("GC_BUTTON_X", 0x0200),
        ("GC_BUTTON_Y", 0x0100),
        ("GC_BUTTON_SOUTH", 0x0040),
        ("GC_BUTTON_EAST", 0x0080),
        ("GC_BUTTON_WEST", 0x0100),
        ("GC_BUTTON_NORTH", 0x0200),
        ("GC_DPAD_UP", 0x0008),
        ("GC_DPAD_DOWN", 0x0004),
        ("GC_DPAD_LEFT", 0x0002),
        ("GC_DPAD_RIGHT", 0x0001),
        ("GC_LEFT_SHOULDER", 0x0400),
        ("GC_RIGHT_SHOULDER", 0x0800),
        ("GC_BUTTON_MENU", 0x0010),
        ("GC_BUTTON_OPTIONS", 0x0020),
        ("GC_LEFT_TRIGGER", 0x1000),
        ("GC_RIGHT_TRIGGER", 0x2000),
        ("GC_LEFT_STICK_BUTTON", 0x4000),
        ("GC_RIGHT_STICK_BUTTON", 0x8000),
        ("GC_AXIS_LEFT_X", 0),
        ("GC_AXIS_LEFT_Y", 1),
        ("GC_AXIS_RIGHT_X", 2),
        ("GC_AXIS_RIGHT_Y", 3),
        ("GC_AXIS_LEFT_TRIGGER", 4),
        ("GC_AXIS_RIGHT_TRIGGER", 5),
        ("PAD_RIGHT", 1 << 0),
        ("PAD_LEFT", 1 << 1),
        ("PAD_DOWN", 1 << 2),
        ("PAD_UP", 1 << 3),
        ("PAD_START", 1 << 4),
        ("PAD_SELECT", 1 << 5),
        ("PAD_B", 1 << 6),
        ("PAD_A", 1 << 7),
        ("PAD_Y", 1 << 8),
        ("PAD_X", 1 << 9),
        ("PAD_L", 1 << 10),
        ("PAD_R", 1 << 11),
        ("PAD_LEFT_TRIGGER", 1 << 12),
        ("PAD_RIGHT_TRIGGER", 1 << 13),
        ("PAD_LEFT_STICK", 1 << 14),
        ("PAD_RIGHT_STICK", 1 << 15),
    ] {
        add_const(&mut gc, name, val, "GameController");
    }
    for alias in [
        "GameController",
        "gamecontroller",
        "lib/gamecontroller",
        "gamepad",
        "lib/gamepad",
    ] {
        libraries.insert(alias.to_string(), gc.clone());
    }

    for lib_map in libraries.values() {
        for (sym, info) in lib_map {
            symbol_to_lib.entry(sym.clone()).or_insert_with(|| info.library.clone());
        }
    }

    (libraries, symbol_to_lib)
}

pub fn get_library(raw_path: &str) -> Option<HashMap<String, SymbolInfo>> {
    let (libraries, _) = build_libraries();
    let clean = normalize_lib_path(raw_path);
    if let Some(m) = libraries.get(&clean) {
        return Some(m.clone());
    }
    let lower = clean.to_ascii_lowercase();
    if let Some(m) = libraries.get(&lower) {
        return Some(m.clone());
    }
    if let Some(without) = clean.strip_prefix("lib/") {
        if let Some(m) = libraries.get(without) {
            return Some(m.clone());
        }
        if let Some(m) = libraries.get(&without.to_ascii_lowercase()) {
            return Some(m.clone());
        }
    } else {
        let with_lib = format!("lib/{}", clean);
        if let Some(m) = libraries.get(&with_lib) {
            return Some(m.clone());
        }
        if let Some(m) = libraries.get(&with_lib.to_ascii_lowercase()) {
            return Some(m.clone());
        }
    }
    None
}

pub fn get_suggested_library_for_symbol(symbol: &str) -> Option<String> {
    let (_, sym_map) = build_libraries();
    sym_map.get(symbol).cloned()
}
