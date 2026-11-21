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
    return (int(hex_str[0:2], 16), int(hex_str[2:4], 16), int(hex_str[4:6], 16), alpha)

def create_grid(w, h, fill_color):
    return [[fill_color for _ in range(w)] for _ in range(h)]

# 1. Architecture Writeup Item (16x16)
def gen_architecture_writeup():
    grid = create_grid(16, 16, (0, 0, 0, 0))
    c_shadow = hex_to_rgba("#04131f")
    c_bg     = hex_to_rgba("#0d3f66")
    c_mid    = hex_to_rgba("#175687")
    c_line   = hex_to_rgba("#45a2d9")
    c_bright = hex_to_rgba("#bfe3ff")
    c_fold   = hex_to_rgba("#2c7bb6")

    # Document body (2,1 to 13,14)
    for y in range(2, 15):
        for x in range(2, 14):
            grid[y][x] = c_bg

    # Outer border
    for x in range(2, 14):
        grid[2][x] = c_shadow
        grid[14][x] = c_shadow
    for y in range(2, 15):
        grid[y][2] = c_shadow
        grid[y][13] = c_shadow

    # Folded top-right corner
    grid[2][12] = (0, 0, 0, 0)
    grid[2][13] = (0, 0, 0, 0)
    grid[3][13] = (0, 0, 0, 0)
    grid[3][11] = c_shadow
    grid[3][12] = c_fold
    grid[4][12] = c_shadow

    # Blueprint circuit schematic in the center
    # CPU square
    for y in range(6, 11):
        for x in range(5, 11):
            if x == 5 or x == 10 or y == 6 or y == 10:
                grid[y][x] = c_bright
            else:
                grid[y][x] = c_mid

    # Core chip center dot
    grid[8][7] = c_bright
    grid[8][8] = c_bright

    # Traces extending outward
    grid[5][7] = c_line
    grid[5][8] = c_line
    grid[11][7] = c_line
    grid[11][8] = c_line
    grid[8][4] = c_line
    grid[8][11] = c_line
    grid[6][4] = c_line
    grid[10][11] = c_line

    return grid

# 2. Architecture Writer Block - Top (16x16)
def gen_architecture_writer_top():
    grid = create_grid(16, 16, hex_to_rgba("#5a3821"))
    wood_dark = hex_to_rgba("#3b2212")
    wood_lit  = hex_to_rgba("#7b4f30")
    bp_border = hex_to_rgba("#061c2d")
    bp_bg     = hex_to_rgba("#0d3f66")
    bp_grid   = hex_to_rgba("#175687")
    bp_trace  = hex_to_rgba("#45a2d9")
    ruler_wood= hex_to_rgba("#d4ac0d")
    ruler_mark= hex_to_rgba("#7d6608")

    # Wood frame
    for x in range(16):
        grid[0][x] = wood_lit
        grid[15][x] = wood_dark
    for y in range(16):
        grid[y][0] = wood_lit
        grid[y][15] = wood_dark

    # Blueprint drafting sheet on top (x=2..12, y=2..13)
    for y in range(2, 14):
        for x in range(2, 13):
            if x == 2 or x == 12 or y == 2 or y == 13:
                grid[y][x] = bp_border
            elif (x % 3 == 0) or (y % 3 == 0):
                grid[y][x] = bp_grid
            else:
                grid[y][x] = bp_bg

    # Schematics on blueprint
    for x in range(5, 10):
        grid[5][x] = bp_trace
        grid[9][x] = bp_trace
    for y in range(5, 10):
        grid[y][5] = bp_trace
        grid[y][9] = bp_trace
    grid[7][7] = hex_to_rgba("#bfe3ff")

    # Drafting ruler on right side (x=13..14, y=3..12)
    for y in range(3, 13):
        grid[y][14] = ruler_wood
        if y % 2 == 0:
            grid[y][13] = ruler_mark
        else:
            grid[y][13] = ruler_wood

    return grid

# 3. Architecture Writer Block - Front (16x16)
def gen_architecture_writer_front():
    grid = create_grid(16, 16, hex_to_rgba("#5a3821"))
    wood_dark = hex_to_rgba("#3b2212")
    wood_lit  = hex_to_rgba("#7b4f30")
    plank_gap = hex_to_rgba("#2a180b")
    brass_lit = hex_to_rgba("#f1c40f")
    brass_dark= hex_to_rgba("#9a7d0a")
    slot_inner= hex_to_rgba("#140d07")
    paper_out = hex_to_rgba("#2980b9")
    paper_lit = hex_to_rgba("#74b9ff")

    # Frame
    for x in range(16):
        grid[0][x] = wood_lit
        grid[15][x] = wood_dark
    for y in range(16):
        grid[y][0] = wood_lit
        grid[y][15] = wood_dark

    # Upper drawer divider
    for x in range(2, 14):
        grid[7][x] = plank_gap

    # Brass drawer pull handle (y=4, x=6..9)
    grid[4][6] = brass_dark
    grid[4][7] = brass_lit
    grid[4][8] = brass_lit
    grid[4][9] = brass_dark
    grid[3][7] = brass_lit
    grid[3][8] = brass_lit

    # Blueprint dispensing aperture (y=10..12, x=3..12)
    for y in range(10, 13):
        for x in range(3, 13):
            grid[y][x] = slot_inner

    # Blueprint paper peeking out of slot
    for x in range(4, 12):
        grid[11][x] = paper_out
        grid[12][x] = paper_lit

    return grid

# 4. Architecture Writer Block - Side (16x16)
def gen_architecture_writer_side():
    grid = create_grid(16, 16, hex_to_rgba("#5a3821"))
    wood_dark = hex_to_rgba("#3b2212")
    wood_lit  = hex_to_rgba("#7b4f30")
    panel_in  = hex_to_rgba("#4d2f1b")
    brace_col = hex_to_rgba("#6e4428")

    # Outer frame
    for x in range(16):
        grid[0][x] = wood_lit
        grid[15][x] = wood_dark
    for y in range(16):
        grid[y][0] = wood_lit
        grid[y][15] = wood_dark

    # Inset panel
    for y in range(2, 14):
        for x in range(2, 14):
            grid[y][x] = panel_in

    # Diagonal brace
    for i in range(2, 14):
        grid[i][i] = brace_col
        if i + 1 < 14:
            grid[i][i+1] = wood_lit
        if i - 1 >= 2:
            grid[i][i-1] = wood_dark

    return grid

# 5. Architecture Writer Block - Bottom (16x16)
def gen_architecture_writer_bottom():
    grid = create_grid(16, 16, hex_to_rgba("#3e2718"))
    dark = hex_to_rgba("#29190d")
    lit  = hex_to_rgba("#52341f")
    for x in range(16):
        grid[0][x] = lit
        grid[15][x] = dark
    for y in range(16):
        grid[y][0] = lit
        grid[y][15] = dark
    # Planks lines
    for x in range(1, 15):
        grid[5][x] = dark
        grid[10][x] = dark
    return grid

base_res = "/home/jvrcruz/IdeaProjects/SiliconCraft/src/main/resources/assets/siliconcraft"
item_dir = os.path.join(base_res, "textures/item")
block_dir = os.path.join(base_res, "textures/block")

save_image_with_ffmpeg(16, 16, gen_architecture_writeup(), os.path.join(item_dir, "architecture_writeup.png"))
save_image_with_ffmpeg(16, 16, gen_architecture_writer_top(), os.path.join(block_dir, "architecture_writer_top.png"))
save_image_with_ffmpeg(16, 16, gen_architecture_writer_front(), os.path.join(block_dir, "architecture_writer_front.png"))
save_image_with_ffmpeg(16, 16, gen_architecture_writer_side(), os.path.join(block_dir, "architecture_writer_side.png"))
save_image_with_ffmpeg(16, 16, gen_architecture_writer_bottom(), os.path.join(block_dir, "architecture_writer_bottom.png"))

print("Architecture Writer and Writeup textures generated successfully!")
