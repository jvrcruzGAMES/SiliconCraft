#!/usr/bin/env python3
import subprocess
import os

def save_image_with_ffmpeg(width, height, pixels_rgba, output_path):
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    byte_data = bytearray()
    for row in pixels_rgba:
        for pixel in row:
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
        return (int(hex_str[0:2], 16), int(hex_str[2:4], 16), int(hex_str[4:6], 16), alpha)
    elif len(hex_str) == 8:
        return (int(hex_str[0:2], 16), int(hex_str[2:4], 16), int(hex_str[4:6], 16), int(hex_str[6:8], 16))
    raise ValueError(f"Invalid hex {hex_str}")

def create_grid(w, h, fill_color):
    return [[fill_color for _ in range(w)] for _ in range(h)]

# Base metal texture helper
def gen_metal_base(accent_hue="#2a3b4c"):
    grid = create_grid(16, 16, hex_to_rgba("#373c42"))
    c_rim = hex_to_rgba("#4f565e")
    c_dark = hex_to_rgba("#22262a")
    for x in range(16):
        grid[0][x] = c_rim
        grid[15][x] = c_dark
    for y in range(16):
        grid[y][0] = c_rim
        grid[y][15] = c_dark
    # Rivets
    c_rivet = hex_to_rgba("#707882")
    for rx, ry in [(1,1), (14,1), (1,14), (14,14)]:
        grid[ry][rx] = c_rivet
    return grid

# Assembly Writer Textures
def gen_assembly_writer_front():
    grid = gen_metal_base()
    c_screen_border = hex_to_rgba("#11151a")
    c_screen_bg = hex_to_rgba("#0f2316")
    c_text = hex_to_rgba("#00e676")
    c_led_green = hex_to_rgba("#00ff66")
    c_led_amber = hex_to_rgba("#ffaa00")
    c_slot = hex_to_rgba("#15181c")
    c_slot_rim = hex_to_rgba("#5a626a")

    # Monitor (x=3..12, y=3..8)
    for y in range(3, 9):
        for x in range(3, 13):
            if x == 3 or x == 12 or y == 3 or y == 8:
                grid[y][x] = c_screen_border
            else:
                grid[y][x] = c_screen_bg
    # "ASM" letters on screen
    # 'A'
    grid[5][5] = c_text; grid[6][5] = c_text; grid[5][6] = c_text; grid[6][7] = c_text; grid[5][7] = c_text
    # 'S'
    grid[5][8] = c_text; grid[6][9] = c_text; grid[5][9] = c_text
    # 'M'
    grid[5][10] = c_text; grid[6][10] = c_text; grid[5][11] = c_text; grid[6][11] = c_text

    # LEDs
    grid[10][3] = c_led_green
    grid[10][5] = c_led_amber

    # Tape/Card drive slot (x=7..13, y=11..13)
    for y in range(11, 14):
        for x in range(7, 14):
            if y == 11 or y == 13 or x == 7 or x == 13:
                grid[y][x] = c_slot_rim
            else:
                grid[y][x] = c_slot
    return grid

def gen_assembly_writer_top():
    grid = gen_metal_base()
    c_plate = hex_to_rgba("#2b3238")
    c_lines = hex_to_rgba("#44e08c")
    c_key_dark = hex_to_rgba("#1b1e22")
    c_key_lit = hex_to_rgba("#7a838c")

    # Workpad in center (x=3..12, y=3..12)
    for y in range(3, 13):
        for x in range(3, 13):
            grid[y][x] = c_plate

    # Keyboard / keypad lines
    for y in range(5, 11, 2):
        for x in range(4, 12, 2):
            grid[y][x] = c_key_lit
            grid[y+1][x] = c_key_dark

    # Code trace accent
    for x in range(4, 12):
        grid[3][x] = c_lines
    return grid

def gen_assembly_writer_side():
    grid = gen_metal_base()
    c_vent = hex_to_rgba("#16181b")
    # Vent slots
    for vy in range(4, 12, 2):
        for vx in range(3, 13):
            grid[vy][vx] = c_vent
    return grid

def gen_assembly_writer_bottom():
    grid = create_grid(16, 16, hex_to_rgba("#25282c"))
    c_dark = hex_to_rgba("#17191c")
    for x in range(16):
        grid[0][x] = c_dark
        grid[15][x] = c_dark
    for y in range(16):
        grid[y][0] = c_dark
        grid[y][15] = c_dark
    return grid

# Code Compiler Textures
def gen_code_compiler_front():
    grid = gen_metal_base()
    c_screen_border = hex_to_rgba("#0f1a24")
    c_screen_bg = hex_to_rgba("#091b29")
    c_cyan = hex_to_rgba("#00e5ff")
    c_magenta = hex_to_rgba("#e040fb")
    c_slot = hex_to_rgba("#15181c")
    c_slot_rim = hex_to_rgba("#5a626a")

    # Monitor (x=3..12, y=3..8)
    for y in range(3, 9):
        for x in range(3, 13):
            if x == 3 or x == 12 or y == 3 or y == 8:
                grid[y][x] = c_screen_border
            else:
                grid[y][x] = c_screen_bg

    # ".N" logo
    grid[6][5] = c_magenta # dot
    # 'N'
    grid[5][7] = c_cyan; grid[6][7] = c_cyan; grid[7][7] = c_cyan
    grid[6][8] = c_cyan
    grid[5][9] = c_cyan; grid[6][9] = c_cyan; grid[7][9] = c_cyan

    # Binary compiler lights
    grid[10][3] = c_cyan
    grid[10][5] = c_magenta
    grid[10][7] = hex_to_rgba("#00ff88")

    # Writeup insert tray (x=3..12, y=12..13)
    for x in range(3, 13):
        grid[12][x] = c_slot_rim
        grid[13][x] = c_slot
    return grid

def gen_code_compiler_top():
    grid = gen_metal_base()
    c_plate = hex_to_rgba("#1b2530")
    c_cyan = hex_to_rgba("#00e5ff")
    c_core = hex_to_rgba("#123e59")

    # Chip synthesis bed
    for y in range(3, 13):
        for x in range(3, 13):
            grid[y][x] = c_plate

    # Central synthesis core
    for y in range(6, 10):
        for x in range(6, 10):
            grid[y][x] = c_core

    # Glowing circuitry traces
    grid[4][7] = c_cyan; grid[5][7] = c_cyan
    grid[10][8] = c_cyan; grid[11][8] = c_cyan
    grid[7][4] = c_cyan; grid[7][5] = c_cyan
    grid[8][10] = c_cyan; grid[8][11] = c_cyan
    return grid

def gen_code_compiler_side():
    grid = gen_metal_base()
    c_copper = hex_to_rgba("#b87333")
    c_dark = hex_to_rgba("#181b1e")
    # Heat sink fins
    for y in range(4, 12):
        for x in range(4, 12):
            if x % 2 == 0:
                grid[y][x] = c_dark
            else:
                grid[y][x] = c_copper
    return grid

def gen_code_compiler_bottom():
    return gen_assembly_writer_bottom()

# Generate GUI helper
def create_base_gui(w=176, h=166):
    grid = [[(0, 0, 0, 0) for _ in range(256)] for _ in range(256)]
    bg_main = hex_to_rgba('#c6c6c6')
    bg_dark = hex_to_rgba('#555555')
    bg_lit  = hex_to_rgba('#ffffff')
    slot_bg = hex_to_rgba('#8b8b8b')
    slot_shadow = hex_to_rgba('#373737')
    
    for y in range(h):
        for x in range(w):
            grid[y][x] = bg_main
            
    for x in range(w):
        grid[0][x] = bg_lit; grid[1][x] = bg_lit
        grid[h-1][x] = bg_dark; grid[h-2][x] = bg_dark
    for y in range(h):
        grid[y][0] = bg_lit; grid[y][1] = bg_lit
        grid[y][w-1] = bg_dark; grid[y][w-2] = bg_dark

    def draw_slot(sx, sy, outline_type=None):
        for y in range(sy, sy + 18):
            for x in range(sx, sx + 18):
                if x == sx or y == sy:
                    grid[y][x] = slot_shadow
                elif x == sx + 17 or y == sy + 17:
                    grid[y][x] = bg_lit
                else:
                    grid[y][x] = slot_bg
        if outline_type == 'writeup':
            # Architecture writeup blueprint outline
            c = hex_to_rgba('#45a2d9')
            # Folded blueprint outline in 16x16 slot area
            for bx in range(sx + 3, sx + 14):
                grid[sy + 3][bx] = c
                grid[sy + 14][bx] = c
            for by in range(sy + 3, sy + 15):
                grid[by][sx + 3] = c
                grid[by][sx + 14] = c
            # Inner chip symbol
            for by in range(sy + 7, sy + 11):
                for bx in range(sx + 7, sx + 11):
                    grid[by][bx] = c

    def draw_energy_bar(ex, ey, ew, eh):
        for y in range(ey, ey + eh):
            for x in range(ex, ex + ew):
                if x == ex or y == ey:
                    grid[y][x] = slot_shadow
                elif x == ex + ew - 1 or y == ey + eh - 1:
                    grid[y][x] = bg_lit
                else:
                    grid[y][x] = hex_to_rgba('#3a1010')

    # Draw inventory (3x9) at y=83, hotbar (1x9) at y=141
    for row in range(3):
        for col in range(9):
            draw_slot(7 + col * 18, 83 + row * 18)
    for col in range(9):
        draw_slot(7 + col * 18, 141)

    return grid, draw_slot, draw_energy_bar

# Build Programmer GUI
def build_programmer_gui():
    g, ds, deb = create_base_gui(176, 166)
    deb(9, 18, 12, 50) # Energy bar
    c_shadow = hex_to_rgba('#373737')
    c_lit = hex_to_rgba('#ffffff')
    c_box_bg = hex_to_rgba('#14181c')

    # Slots
    ds(26, 49) # Fuel
    ds(26, 19) # ROM input
    ds(48, 19) # ROM output

    # Right side: Programs list frame (x=70 to 168, y=14 to 61)
    for y in range(14, 62):
        for x in range(70, 169):
            if x == 70 or y == 14:
                g[y][x] = c_shadow
            elif x == 168 or y == 61:
                g[y][x] = c_lit
            else:
                g[y][x] = c_box_bg

    # Scrollbar track (x=162..167, y=15..60)
    for y in range(15, 61):
        g[y][162] = hex_to_rgba('#252a30')
        g[y][167] = hex_to_rgba('#3a4048')

    return g

# Build Assembly Writer GUI
def build_assembly_writer_gui():
    g, ds, deb = create_base_gui(176, 166)
    c_shadow = hex_to_rgba('#373737')
    c_lit = hex_to_rgba('#ffffff')
    c_box_bg = hex_to_rgba('#14181c')

    # Slot 0 at (26, 34)
    ds(26, 34, 'writeup')

    # File preview / validation frame on the right (x=72 to 168, y=34 to 62)
    for y in range(34, 63):
        for x in range(72, 169):
            if x == 72 or y == 34:
                g[y][x] = c_shadow
            elif x == 168 or y == 62:
                g[y][x] = c_lit
            else:
                g[y][x] = c_box_bg

    return g

# Build Code Compiler GUI
def build_code_compiler_gui():
    g, ds, deb = create_base_gui(176, 166)
    c_shadow = hex_to_rgba('#373737')
    c_lit = hex_to_rgba('#ffffff')
    c_box_bg = hex_to_rgba('#101824')

    # Slot 0 at (26, 34)
    ds(26, 34, 'writeup')

    # Compiler preview / validation frame on the right (x=72 to 168, y=34 to 62)
    for y in range(34, 63):
        for x in range(72, 169):
            if x == 72 or y == 34:
                g[y][x] = c_shadow
            elif x == 168 or y == 62:
                g[y][x] = c_lit
            else:
                g[y][x] = c_box_bg

    return g

def main():
    block_dir = "src/main/resources/assets/siliconcraft/textures/block"
    gui_dir = "src/main/resources/assets/siliconcraft/textures/gui"

    # Blocks
    save_image_with_ffmpeg(16, 16, gen_assembly_writer_front(), f"{block_dir}/assembly_writer_front.png")
    save_image_with_ffmpeg(16, 16, gen_assembly_writer_top(), f"{block_dir}/assembly_writer_top.png")
    save_image_with_ffmpeg(16, 16, gen_assembly_writer_side(), f"{block_dir}/assembly_writer_side.png")
    save_image_with_ffmpeg(16, 16, gen_assembly_writer_bottom(), f"{block_dir}/assembly_writer_bottom.png")

    save_image_with_ffmpeg(16, 16, gen_code_compiler_front(), f"{block_dir}/code_compiler_front.png")
    save_image_with_ffmpeg(16, 16, gen_code_compiler_top(), f"{block_dir}/code_compiler_top.png")
    save_image_with_ffmpeg(16, 16, gen_code_compiler_side(), f"{block_dir}/code_compiler_side.png")
    save_image_with_ffmpeg(16, 16, gen_code_compiler_bottom(), f"{block_dir}/code_compiler_bottom.png")

    # GUIs
    save_image_with_ffmpeg(256, 256, build_programmer_gui(), f"{gui_dir}/programmer.png")
    save_image_with_ffmpeg(256, 256, build_assembly_writer_gui(), f"{gui_dir}/assembly_writer.png")
    save_image_with_ffmpeg(256, 256, build_code_compiler_gui(), f"{gui_dir}/code_compiler.png")

if __name__ == "__main__":
    main()
