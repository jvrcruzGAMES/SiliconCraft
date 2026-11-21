#!/usr/bin/env python3
import subprocess
import os

def save_image_with_ffmpeg(width, height, pixels_rgba, output_path):
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    byte_data = bytearray()
    for row in pixels_rgba:
        for pixel in row:
            # pixel is (r, g, b, a)
            byte_data.extend(pixel)
    
    cmd = [
        'ffmpeg', '-y',
        '-f', 'rawvideo',
        '-pixel_format', 'rgba',
        '-video_size', f'{width}x{height}',
        '-i', '-',
        output_path
    ]
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    stdout, stderr = p.communicate(input=byte_data)
    if p.returncode != 0:
        raise RuntimeError(f"FFmpeg failed for {output_path}: {stderr.decode('utf-8', errors='ignore')}")
    print(f"Generated {output_path}")

def hex_to_rgba(hex_str, alpha=255):
    hex_str = hex_str.lstrip('#')
    if len(hex_str) == 6:
        r = int(hex_str[0:2], 16)
        g = int(hex_str[2:4], 16)
        b = int(hex_str[4:6], 16)
        return (r, g, b, alpha)
    elif len(hex_str) == 8:
        r = int(hex_str[0:2], 16)
        g = int(hex_str[2:4], 16)
        b = int(hex_str[4:6], 16)
        a = int(hex_str[6:8], 16)
        return (r, g, b, a)
    raise ValueError(f"Invalid hex color {hex_str}")

def create_grid(w, h, fill_color):
    return [[fill_color for _ in range(w)] for _ in range(h)]

# --- 1. Silicon Powder ---
def gen_silicon_powder():
    grid = create_grid(16, 16, (0, 0, 0, 0))
    # Palette
    C0 = (0, 0, 0, 0)
    C1 = hex_to_rgba("#202830") # shadow
    C2 = hex_to_rgba("#3b4c5a") # dark slate
    C3 = hex_to_rgba("#5b7388") # mid blue-gray
    C4 = hex_to_rgba("#8ea4b8") # light steel
    C5 = hex_to_rgba("#c4daf0") # bright shine
    C6 = hex_to_rgba("#eef6ff") # sparkle

    art = [
        [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
        [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
        [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
        [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
        [0,0,0,0,0,0,0,6,0,0,0,0,0,0,0,0],
        [0,0,0,0,0,0,5,4,5,0,0,0,0,0,0,0],
        [0,0,0,0,0,4,5,6,4,5,0,0,0,0,0,0],
        [0,0,0,0,5,3,4,5,3,4,5,0,0,0,0,0],
        [0,0,0,4,3,4,6,4,4,3,4,3,0,0,0,0],
        [0,0,4,3,5,4,3,3,5,4,2,3,4,0,0,0],
        [0,0,3,4,3,4,5,4,3,4,3,4,3,0,0,0],
        [0,4,3,2,3,4,3,4,3,2,3,3,2,4,0,0],
        [0,3,2,3,4,3,2,3,4,3,2,3,2,3,0,0],
        [0,1,2,1,2,3,2,2,3,2,2,1,2,1,0,0],
        [0,0,1,1,1,1,2,1,1,2,1,1,1,0,0,0],
        [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]
    ]
    pal = [C0, C1, C2, C3, C4, C5, C6]
    for y in range(16):
        for x in range(16):
            grid[y][x] = pal[art[y][x]]
    return grid

# --- 2. Silicon Ore ---
def gen_silicon_ore():
    # Stone texture with silicon crystal inclusions
    grid = create_grid(16, 16, hex_to_rgba("#686868"))
    stone_dark = hex_to_rgba("#505050")
    stone_mid  = hex_to_rgba("#686868")
    stone_lit  = hex_to_rgba("#7e7e7e")
    ore_shadow = hex_to_rgba("#1d2b3a")
    ore_base   = hex_to_rgba("#32527b")
    ore_mid    = hex_to_rgba("#5182ba")
    ore_lit    = hex_to_rgba("#88bcf0")
    ore_glint  = hex_to_rgba("#e2f0ff")

    # Stone pattern
    for y in range(16):
        for x in range(16):
            if (x * 3 + y * 7) % 5 == 0:
                grid[y][x] = stone_dark
            elif (x * 7 + y * 3) % 4 == 0:
                grid[y][x] = stone_lit
            else:
                grid[y][x] = stone_mid

    # Ore clusters
    crystals = [
        # cluster 1
        (3, 3, ore_glint), (4, 3, ore_lit), (5, 3, ore_base),
        (3, 4, ore_lit),   (4, 4, ore_mid), (5, 4, ore_shadow),
        (4, 5, ore_shadow),
        # cluster 2
        (10, 4, ore_lit),  (11, 4, ore_glint),
        (10, 5, ore_base), (11, 5, ore_mid), (12, 5, ore_shadow),
        (11, 6, ore_shadow),
        # cluster 3
        (6, 9, ore_lit),   (7, 9, ore_glint), (8, 9, ore_lit),
        (5, 10, ore_base), (6, 10, ore_mid), (7, 10, ore_base), (8, 10, ore_shadow),
        (6, 11, ore_shadow), (7, 11, ore_shadow),
        # cluster 4
        (11, 11, ore_glint), (12, 11, ore_lit),
        (11, 12, ore_mid),   (12, 12, ore_base), (13, 12, ore_shadow),
        (12, 13, ore_shadow),
        # cluster 5
        (2, 12, ore_lit), (3, 12, ore_base),
        (2, 13, ore_shadow)
    ]
    for x, y, col in crystals:
        grid[y][x] = col
    return grid

# --- 3. Chip Item ---
def gen_chip():
    grid = create_grid(16, 16, (0, 0, 0, 0))
    pin_gold   = hex_to_rgba("#e6b422")
    pin_dark   = hex_to_rgba("#a67c11")
    body_base  = hex_to_rgba("#1c2b22") # dark tech green
    body_lit   = hex_to_rgba("#2b4234")
    body_edge  = hex_to_rgba("#0f1813")
    die_silicon= hex_to_rgba("#4a709c") # shiny silicon core
    die_core   = hex_to_rgba("#7ea8d8")
    die_shine  = hex_to_rgba("#d0e4ff")
    dot_index  = hex_to_rgba("#88ccaa")

    # Pins left and right
    for y in [3, 5, 7, 9, 11]:
        grid[y][1] = pin_gold
        grid[y][2] = pin_dark
        grid[y][13] = pin_dark
        grid[y][14] = pin_gold

    # Body
    for y in range(2, 13):
        for x in range(3, 13):
            grid[y][x] = body_base

    # Body outline/shading
    for x in range(3, 13):
        grid[2][x] = body_lit
        grid[12][x] = body_edge
    for y in range(2, 13):
        grid[y][3] = body_lit
        grid[y][12] = body_edge

    # Notch/Pin 1 marker
    grid[3][4] = dot_index

    # Silicon die center
    for y in range(5, 10):
        for x in range(5, 11):
            grid[y][x] = die_silicon
    grid[6][7] = die_core
    grid[7][7] = die_core
    grid[6][8] = die_core
    grid[5][6] = die_shine

    return grid

# --- 4. RAM Item ---
def gen_ram():
    grid = create_grid(16, 16, (0, 0, 0, 0))
    pcb_dark = hex_to_rgba("#0a4023")
    pcb_mid  = hex_to_rgba("#136838")
    pcb_lit  = hex_to_rgba("#1e8a4d")
    chip_col = hex_to_rgba("#181818")
    chip_lit = hex_to_rgba("#303030")
    gold_con = hex_to_rgba("#f0c030")
    gold_dark= hex_to_rgba("#9c7510")

    # RAM stick PCB (slanted or horizontal; horizontal fits 16x16 well)
    for y in range(5, 12):
        for x in range(1, 15):
            grid[y][x] = pcb_mid
    for x in range(1, 15):
        grid[5][x] = pcb_lit
        grid[11][x] = pcb_dark

    # Gold connector fingers along bottom
    for x in range(2, 14):
        if x in [7]: # notch
            grid[11][x] = pcb_dark
            grid[12][x] = (0, 0, 0, 0)
        else:
            grid[11][x] = gold_dark
            grid[12][x] = gold_con

    # Memory chips (4 DRAM modules)
    for chip_x in [2, 5, 8, 11]:
        for dy in range(3):
            for dx in range(2):
                grid[7 + dy][chip_x + dx] = chip_col
        grid[7][chip_x] = chip_lit

    return grid

# --- 5. ROM Item ---
def gen_rom():
    grid = create_grid(16, 16, (0, 0, 0, 0))
    pin_gold   = hex_to_rgba("#e6b422")
    pin_dark   = hex_to_rgba("#966f12")
    body_base  = hex_to_rgba("#2b1e36") # EEPROM deep purple
    body_lit   = hex_to_rgba("#463158")
    body_edge  = hex_to_rgba("#150d1c")
    window_blue= hex_to_rgba("#2c6d9e") # Quartz erase window
    window_lit = hex_to_rgba("#61a9de")
    window_core= hex_to_rgba("#9ed6ff")

    # Pins
    for y in [3, 5, 7, 9, 11, 13]:
        grid[y][2] = pin_gold
        grid[y][3] = pin_dark
        grid[y][12] = pin_dark
        grid[y][13] = pin_gold

    # Body
    for y in range(2, 15):
        for x in range(4, 12):
            grid[y][x] = body_base
    for x in range(4, 12):
        grid[2][x] = body_lit
        grid[14][x] = body_edge
    for y in range(2, 15):
        grid[y][4] = body_lit
        grid[y][11] = body_edge

    # Center quartz window (Classic UV-EPROM style)
    for y in range(6, 10):
        for x in range(6, 10):
            grid[y][x] = window_blue
    grid[6][6] = window_lit
    grid[7][7] = window_core

    return grid

# --- 6. Storage Item (NVMe) ---
def gen_storage():
    grid = create_grid(16, 16, (0, 0, 0, 0))
    pcb = hex_to_rgba("#142230")
    pcb_lit = hex_to_rgba("#233d57")
    gold = hex_to_rgba("#e6b422")
    gold_dark = hex_to_rgba("#9c7510")
    nand = hex_to_rgba("#1b1b1b")
    nand_lit = hex_to_rgba("#353535")
    ctrl = hex_to_rgba("#9c3a28") # High performance controller (metal red)
    label_w = hex_to_rgba("#e0e0e0")

    # NVMe M.2 Card shape (vertical)
    for y in range(1, 15):
        for x in range(4, 12):
            grid[y][x] = pcb
    for y in range(1, 15):
        grid[y][4] = pcb_lit
    for x in range(4, 12):
        grid[1][x] = pcb_lit

    # Screw notch top
    grid[1][7] = (0, 0, 0, 0)
    grid[1][8] = (0, 0, 0, 0)

    # Controller chip
    for y in range(3, 6):
        for x in range(6, 10):
            grid[y][x] = ctrl

    # NAND Flash 1
    for y in range(7, 10):
        for x in range(5, 11):
            grid[y][x] = nand
        grid[7][5] = nand_lit

    # NAND Flash 2
    for y in range(11, 14):
        for x in range(5, 11):
            grid[y][x] = nand
        grid[11][5] = nand_lit

    # M-Key PCIe gold contacts bottom
    for x in range(4, 12):
        if x == 7: # M key notch
            grid[14][x] = pcb
        else:
            grid[14][x] = gold
            grid[15][x] = gold_dark

    return grid

# --- 7. SoC Item ---
def gen_soc():
    grid = create_grid(16, 16, (0, 0, 0, 0))
    sub_base = hex_to_rgba("#1a4032") # Dark green substrate
    sub_lit  = hex_to_rgba("#2b644f")
    sub_edge = hex_to_rgba("#0d241c")
    ihs_metal= hex_to_rgba("#a4b0bd") # Integrated Heat Spreader (Nickel/Silver)
    ihs_lit  = hex_to_rgba("#d5dee8")
    ihs_dark = hex_to_rgba("#677380")
    ihs_hi   = hex_to_rgba("#ffffff")
    gold_dot = hex_to_rgba("#e8b828")
    gold_trace = hex_to_rgba("#9c7a1a")

    # Substrate
    for y in range(1, 15):
        for x in range(1, 15):
            grid[y][x] = sub_base
    for x in range(1, 15):
        grid[1][x] = sub_lit
        grid[14][x] = sub_edge
    for y in range(1, 15):
        grid[y][1] = sub_lit
        grid[y][14] = sub_edge

    # Alignment triangle / Pin 1
    grid[2][2] = gold_dot

    # Heat spreader (IHS)
    for y in range(3, 13):
        for x in range(3, 13):
            grid[y][x] = ihs_metal
    for x in range(3, 13):
        grid[3][x] = ihs_lit
        grid[12][x] = ihs_dark
    for y in range(3, 13):
        grid[y][3] = ihs_lit
        grid[y][12] = ihs_dark

    # Center metallic reflection / etched silicon crest
    grid[4][4] = ihs_hi
    grid[7][7] = ihs_lit
    grid[7][8] = ihs_dark
    grid[8][7] = ihs_lit
    grid[8][8] = ihs_dark

    return grid

# --- 8. Computer Case Item & Block textures ---
def gen_computer_case_front():
    grid = create_grid(16, 16, hex_to_rgba("#1f2226"))
    bevel_lit  = hex_to_rgba("#383d45")
    bevel_dark = hex_to_rgba("#101214")
    mesh       = hex_to_rgba("#121417")
    mesh_dot   = hex_to_rgba("#26292e")
    pwr_slot   = hex_to_rgba("#444952")
    usb_port   = hex_to_rgba("#0a0b0d")

    # Border bevel
    for x in range(16):
        grid[0][x] = bevel_lit
        grid[15][x] = bevel_dark
    for y in range(16):
        grid[y][0] = bevel_lit
        grid[y][15] = bevel_dark

    # Top I/O panel
    grid[2][4] = pwr_slot # Empty power button
    grid[2][10] = usb_port
    grid[2][12] = usb_port

    # Front Intake Mesh
    for y in range(5, 14):
        for x in range(3, 13):
            if (x + y) % 2 == 0:
                grid[y][x] = mesh
            else:
                grid[y][x] = mesh_dot

    return grid

def gen_computer_front_off():
    # Similar to case but power button and status indicators installed
    grid = gen_computer_case_front()
    pwr_btn = hex_to_rgba("#68717d")
    led_off = hex_to_rgba("#6e1e1e") # Dim red LED
    grid[2][4] = pwr_btn
    grid[2][6] = led_off
    return grid

def gen_computer_front_on():
    grid = gen_computer_case_front()
    pwr_btn = hex_to_rgba("#a0b2c6")
    led_on  = hex_to_rgba("#2ae84a") # Glowing green LED
    fan_rgb = hex_to_rgba("#3388ff") # RGB lighting behind intake mesh
    grid[2][4] = pwr_btn
    grid[2][6] = led_on

    # RGB glow in mesh
    for y in range(6, 13):
        for x in range(4, 12):
            if (x - 7.5)**2 + (y - 9.5)**2 <= 14:
                if (x + y) % 2 == 0:
                    grid[y][x] = fan_rgb
    return grid

def gen_metal_side():
    grid = create_grid(16, 16, hex_to_rgba("#24272c"))
    bevel_lit  = hex_to_rgba("#3b4047")
    bevel_dark = hex_to_rgba("#131518")
    for x in range(16):
        grid[0][x] = bevel_lit
        grid[15][x] = bevel_dark
    for y in range(16):
        grid[y][0] = bevel_lit
        grid[y][15] = bevel_dark
    # Texture detail
    for y in range(4, 12):
        grid[y][3] = bevel_dark
        grid[y][12] = bevel_lit
    return grid

def gen_metal_top():
    grid = create_grid(16, 16, hex_to_rgba("#282b30"))
    bevel_lit  = hex_to_rgba("#3b4047")
    bevel_dark = hex_to_rgba("#131518")
    vent       = hex_to_rgba("#14161a")
    for x in range(16):
        grid[0][x] = bevel_lit
        grid[15][x] = bevel_dark
    for y in range(16):
        grid[y][0] = bevel_lit
        grid[y][15] = bevel_dark
    # Top exhaust vents
    for y in [4, 6, 8, 10]:
        for x in range(3, 13):
            grid[y][x] = vent
    return grid

# --- 9. Chip Forge Block textures ---
def gen_chip_forge_front(active=False):
    grid = create_grid(16, 16, hex_to_rgba("#2c3138"))
    frame_lit = hex_to_rgba("#474e59")
    frame_dark= hex_to_rgba("#171a1f")
    screen_bg = hex_to_rgba("#121b24")
    screen_lit= hex_to_rgba("#38b5f0") if active else hex_to_rgba("#1f4d66")
    slot_col  = hex_to_rgba("#0f1214")
    gold_con  = hex_to_rgba("#e0a820")
    fire_lit  = hex_to_rgba("#ff9d26") if active else hex_to_rgba("#33241b")
    fire_core = hex_to_rgba("#fff080") if active else hex_to_rgba("#1a120d")

    # Frame
    for x in range(16):
        grid[0][x] = frame_lit
        grid[15][x] = frame_dark
    for y in range(16):
        grid[y][0] = frame_lit
        grid[y][15] = frame_dark

    # Upper panel: Instruction architecture screen
    for y in range(2, 7):
        for x in range(2, 14):
            grid[y][x] = screen_bg
    grid[3][4] = screen_lit
    grid[3][7] = screen_lit
    grid[3][10] = screen_lit
    grid[5][5] = screen_lit
    grid[5][8] = screen_lit

    # Middle slot for wafer/silicon
    for x in range(3, 13):
        grid[8][x] = slot_col
        grid[9][x] = gold_con

    # Lower furnace heating core
    for y in range(11, 14):
        for x in range(4, 12):
            grid[y][x] = fire_lit
    grid[12][7] = fire_core
    grid[12][8] = fire_core

    return grid

# --- 10. Memory Forge Block textures ---
def gen_memory_forge_front(active=False):
    grid = create_grid(16, 16, hex_to_rgba("#252c38"))
    frame_lit = hex_to_rgba("#3e495c")
    frame_dark= hex_to_rgba("#14181f")
    gold_trim = hex_to_rgba("#dca41b")
    gold_dark = hex_to_rgba("#87640f")
    screen_on = hex_to_rgba("#2ef0bb") if active else hex_to_rgba("#155c49")
    screen_bg = hex_to_rgba("#0f211c")
    laser_lit = hex_to_rgba("#32b4ff") if active else hex_to_rgba("#1f3b4d")

    for x in range(16):
        grid[0][x] = frame_lit
        grid[15][x] = frame_dark
    for y in range(16):
        grid[y][0] = frame_lit
        grid[y][15] = frame_dark

    # Gold bands
    for x in range(1, 15):
        grid[2][x] = gold_trim
        grid[3][x] = gold_dark

    # Display / Memory matrix screen
    for y in range(5, 10):
        for x in range(3, 13):
            grid[y][x] = screen_bg
    for y in [6, 8]:
        for x in [4, 6, 8, 10]:
            grid[y][x] = screen_on

    # Lithography chamber aperture bottom
    for y in range(11, 14):
        for x in range(4, 12):
            grid[y][x] = hex_to_rgba("#13161c")
    grid[12][7] = laser_lit
    grid[12][8] = laser_lit

    return grid

# --- 11. Programmer Block textures ---
def gen_programmer_front():
    grid = create_grid(16, 16, hex_to_rgba("#2b2c2e"))
    frame_lit = hex_to_rgba("#424447")
    frame_dark= hex_to_rgba("#151617")
    screen_bg = hex_to_rgba("#09140c")
    green_txt = hex_to_rgba("#33e660")
    socket_bg = hex_to_rgba("#192433")
    pin_col   = hex_to_rgba("#e6b820")

    for x in range(16):
        grid[0][x] = frame_lit
        grid[15][x] = frame_dark
    for y in range(16):
        grid[y][0] = frame_lit
        grid[y][15] = frame_dark

    # Terminal screen top
    for y in range(2, 8):
        for x in range(2, 14):
            grid[y][x] = screen_bg
    # Code cursor / lines on screen
    grid[3][3] = green_txt
    grid[3][5] = green_txt
    grid[3][6] = green_txt
    grid[5][3] = green_txt
    grid[5][4] = green_txt
    grid[5][8] = green_txt

    # ZIF programming socket bottom
    for y in range(10, 14):
        for x in range(4, 12):
            grid[y][x] = socket_bg
    for x in [5, 7, 9, 10]:
        grid[11][x] = pin_col
        grid[12][x] = pin_col

    return grid

# --- 12. SoC Manufacturer Block textures ---
def gen_soc_manufacturer_front():
    grid = create_grid(16, 16, hex_to_rgba("#332822")) # Wood/Metallic industrial base
    frame_lit = hex_to_rgba("#524036")
    frame_dark= hex_to_rgba("#191411")
    steel_lit = hex_to_rgba("#616873")
    steel_dark= hex_to_rgba("#282b30")
    gold_pin  = hex_to_rgba("#e6b422")
    cyan_glow = hex_to_rgba("#3be6c4")

    for x in range(16):
        grid[0][x] = frame_lit
        grid[15][x] = frame_dark
    for y in range(16):
        grid[y][0] = frame_lit
        grid[y][15] = frame_dark

    # Robotic gantry rails
    for x in range(2, 14):
        grid[3][x] = steel_lit
        grid[4][x] = steel_dark

    # Bonding head in center
    grid[5][7] = steel_lit
    grid[5][8] = steel_lit
    grid[6][7] = gold_pin
    grid[6][8] = cyan_glow

    # Fabrication stage / tray bottom
    for y in range(9, 14):
        for x in range(3, 13):
            grid[y][x] = steel_dark
    for x in range(4, 12):
        grid[9][x] = steel_lit
        grid[13][x] = gold_pin

    return grid

def main():
    root = "/home/jvrcruz/IdeaProjects/SiliconCraft/src/main/resources/assets/siliconcraft"
    textures_item = os.path.join(root, "textures/item")
    textures_block = os.path.join(root, "textures/block")

    # Item textures
    save_image_with_ffmpeg(16, 16, gen_silicon_powder(), os.path.join(textures_item, "silicon_powder.png"))
    save_image_with_ffmpeg(16, 16, gen_chip(), os.path.join(textures_item, "chip.png"))
    save_image_with_ffmpeg(16, 16, gen_ram(), os.path.join(textures_item, "ram.png"))
    save_image_with_ffmpeg(16, 16, gen_rom(), os.path.join(textures_item, "rom.png"))
    save_image_with_ffmpeg(16, 16, gen_storage(), os.path.join(textures_item, "storage.png"))
    save_image_with_ffmpeg(16, 16, gen_soc(), os.path.join(textures_item, "soc.png"))
    save_image_with_ffmpeg(16, 16, gen_computer_case_front(), os.path.join(textures_item, "computer_case.png"))
    save_image_with_ffmpeg(16, 16, gen_computer_front_on(), os.path.join(textures_item, "computer.png"))

    # Block textures
    save_image_with_ffmpeg(16, 16, gen_silicon_ore(), os.path.join(textures_block, "silicon_ore.png"))

    save_image_with_ffmpeg(16, 16, gen_chip_forge_front(False), os.path.join(textures_block, "chip_forge_front.png"))
    save_image_with_ffmpeg(16, 16, gen_chip_forge_front(True), os.path.join(textures_block, "chip_forge_front_active.png"))
    save_image_with_ffmpeg(16, 16, gen_metal_side(), os.path.join(textures_block, "chip_forge_side.png"))
    save_image_with_ffmpeg(16, 16, gen_metal_top(), os.path.join(textures_block, "chip_forge_top.png"))
    save_image_with_ffmpeg(16, 16, gen_metal_side(), os.path.join(textures_block, "chip_forge_bottom.png"))

    save_image_with_ffmpeg(16, 16, gen_memory_forge_front(False), os.path.join(textures_block, "memory_forge_front.png"))
    save_image_with_ffmpeg(16, 16, gen_memory_forge_front(True), os.path.join(textures_block, "memory_forge_front_active.png"))
    save_image_with_ffmpeg(16, 16, gen_metal_side(), os.path.join(textures_block, "memory_forge_side.png"))
    save_image_with_ffmpeg(16, 16, gen_metal_top(), os.path.join(textures_block, "memory_forge_top.png"))

    save_image_with_ffmpeg(16, 16, gen_programmer_front(), os.path.join(textures_block, "programmer_front.png"))
    save_image_with_ffmpeg(16, 16, gen_metal_side(), os.path.join(textures_block, "programmer_side.png"))
    save_image_with_ffmpeg(16, 16, gen_metal_top(), os.path.join(textures_block, "programmer_top.png"))

    save_image_with_ffmpeg(16, 16, gen_soc_manufacturer_front(), os.path.join(textures_block, "soc_manufacturer_front.png"))
    save_image_with_ffmpeg(16, 16, gen_metal_side(), os.path.join(textures_block, "soc_manufacturer_side.png"))
    save_image_with_ffmpeg(16, 16, gen_metal_top(), os.path.join(textures_block, "soc_manufacturer_top.png"))

    save_image_with_ffmpeg(16, 16, gen_computer_case_front(), os.path.join(textures_block, "computer_case_front.png"))
    save_image_with_ffmpeg(16, 16, gen_metal_side(), os.path.join(textures_block, "computer_case_side.png"))
    save_image_with_ffmpeg(16, 16, gen_metal_top(), os.path.join(textures_block, "computer_case_top.png"))

    save_image_with_ffmpeg(16, 16, gen_computer_front_off(), os.path.join(textures_block, "computer_front_off.png"))
    save_image_with_ffmpeg(16, 16, gen_computer_front_on(), os.path.join(textures_block, "computer_front_on.png"))
    save_image_with_ffmpeg(16, 16, gen_metal_side(), os.path.join(textures_block, "computer_side.png"))
    save_image_with_ffmpeg(16, 16, gen_metal_top(), os.path.join(textures_block, "computer_top.png"))

    import gen_guis
    print("All textures and GUIs successfully created using FFmpeg!")

if __name__ == "__main__":
    main()
