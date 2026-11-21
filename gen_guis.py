#!/usr/bin/env python3
import subprocess
import os

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

def create_base_gui(w=176, h=166):
    grid = [[(0, 0, 0, 0) for _ in range(256)] for _ in range(256)]
    bg_main = hex_to_rgba('#c6c6c6')
    bg_dark = hex_to_rgba('#555555')
    bg_lit  = hex_to_rgba('#ffffff')
    slot_bg = hex_to_rgba('#8b8b8b')
    slot_shadow = hex_to_rgba('#373737')
    
    # Fill main panel
    for y in range(h):
        for x in range(w):
            grid[y][x] = bg_main
            
    # Bevel border
    for x in range(w):
        grid[0][x] = bg_lit
        grid[1][x] = bg_lit
        grid[h-1][x] = bg_dark
        grid[h-2][x] = bg_dark
    for y in range(h):
        grid[y][0] = bg_lit
        grid[y][1] = bg_lit
        grid[y][w-1] = bg_dark
        grid[y][w-2] = bg_dark

    def draw_slot(sx, sy, outline_type=None):
        for y in range(sy, sy + 18):
            for x in range(sx, sx + 18):
                if x == sx or y == sy:
                    grid[y][x] = slot_shadow
                elif x == sx + 17 or y == sy + 17:
                    grid[y][x] = bg_lit
                else:
                    grid[y][x] = slot_bg
        if outline_type:
            draw_outline(sx + 1, sy + 1, outline_type)

    def draw_outline(ox, oy, otype):
        c = hex_to_rgba('#666666')
        c_accent = hex_to_rgba('#505050')
        # 16x16 interior area from ox, oy to ox+15, oy+15
        if otype == 'fuel': # Flame outline
            flame_pixels = [
                (7,2), (8,2),
                (6,3), (9,3),
                (5,4), (10,4),
                (5,5), (8,5), (10,5),
                (4,6), (7,6), (11,6),
                (4,7), (6,7), (11,7),
                (3,8), (12,8),
                (3,9), (12,9),
                (3,10), (12,10),
                (4,11), (11,11),
                (5,12), (6,12), (7,12), (8,12), (9,12), (10,12)
            ]
            for dx, dy in flame_pixels:
                grid[oy + dy][ox + dx] = c
        elif otype == 'silicon': # Powder pile outline
            silicon_pixels = [
                (7,4), (8,4),
                (6,5), (9,5),
                (5,6), (10,6),
                (4,7), (11,7),
                (4,8), (11,8),
                (3,9), (12,9),
                (3,10), (12,10),
                (2,11), (13,11),
                (2,12), (3,12), (4,12), (5,12), (6,12), (7,12), (8,12), (9,12), (10,12), (11,12), (12,12), (13,12)
            ]
            for dx, dy in silicon_pixels:
                grid[oy + dy][ox + dx] = hex_to_rgba('#556877')
        elif otype == 'redstone': # Redstone dust cross outline
            redstone_pixels = [
                (7,3), (8,3),
                (7,4), (8,4),
                (6,5), (9,5),
                (3,6), (4,6), (5,6), (10,6), (11,6), (12,6),
                (2,7), (13,7),
                (2,8), (13,8),
                (3,9), (4,9), (5,9), (10,9), (11,9), (12,9),
                (6,10), (9,10),
                (7,11), (8,11),
                (7,12), (8,12)
            ]
            for dx, dy in redstone_pixels:
                grid[oy + dy][ox + dx] = hex_to_rgba('#7a4444')
        elif otype == 'gold': # Gold ingot outline
            for dx in range(4, 12):
                grid[oy + 5][ox + dx] = hex_to_rgba('#807030')
                grid[oy + 10][ox + dx] = hex_to_rgba('#807030')
            for dy in range(6, 10):
                grid[oy + dy][ox + 3] = hex_to_rgba('#807030')
                grid[oy + dy][ox + 12] = hex_to_rgba('#807030')
        elif otype == 'diamond': # Diamond gemstone outline
            diamond_pixels = [
                (6,3), (7,3), (8,3), (9,3),
                (4,5), (5,5), (10,5), (11,5),
                (3,6), (12,6),
                (4,7), (11,7),
                (5,9), (10,9),
                (6,11), (9,11),
                (7,13), (8,13)
            ]
            for dx, dy in diamond_pixels:
                grid[oy + dy][ox + dx] = hex_to_rgba('#356e80')
        elif otype == 'chip': # IC chip outline with pins
            for dy in range(4, 12):
                grid[oy + dy][ox + 4] = c_accent
                grid[oy + dy][ox + 11] = c_accent
            for dx in range(4, 12):
                grid[oy + 4][ox + dx] = c_accent
                grid[oy + 11][ox + dx] = c_accent
            for p in [5, 7, 9]:
                grid[oy + p][ox + 2] = c
                grid[oy + p][ox + 3] = c
                grid[oy + p][ox + 12] = c
                grid[oy + p][ox + 13] = c
        elif otype == 'ram': # RAM module outline
            for dx in range(2, 14):
                grid[oy + 5][ox + dx] = hex_to_rgba('#446e4e')
                grid[oy + 10][ox + dx] = hex_to_rgba('#446e4e')
            for dy in range(6, 10):
                grid[oy + dy][ox + 2] = hex_to_rgba('#446e4e')
                grid[oy + dy][ox + 13] = hex_to_rgba('#446e4e')
            for p in [4, 7, 10]:
                grid[oy + 11][ox + p] = hex_to_rgba('#7a6e2e')
        elif otype == 'rom': # ROM DIP chip outline
            for dy in range(3, 13):
                grid[oy + dy][ox + 4] = hex_to_rgba('#654477')
                grid[oy + dy][ox + 11] = hex_to_rgba('#654477')
            for dx in range(4, 12):
                grid[oy + 3][ox + dx] = hex_to_rgba('#654477')
                grid[oy + 12][ox + dx] = hex_to_rgba('#654477')
            for p in [4, 6, 8, 10]:
                grid[oy + p][ox + 2] = hex_to_rgba('#776633')
                grid[oy + p][ox + 13] = hex_to_rgba('#776633')
        elif otype == 'storage': # M.2 NVMe storage card outline
            for dy in range(2, 14):
                grid[oy + dy][ox + 5] = hex_to_rgba('#445866')
                grid[oy + dy][ox + 10] = hex_to_rgba('#445866')
            for dx in range(5, 11):
                grid[oy + 2][ox + dx] = hex_to_rgba('#445866')
                grid[oy + 13][ox + dx] = hex_to_rgba('#445866')
            grid[oy + 14][ox + 6] = hex_to_rgba('#776633')
            grid[oy + 14][ox + 9] = hex_to_rgba('#776633')
        elif otype == 'wood': # Wooden slab outline
            for dx in range(2, 14):
                grid[oy + 7][ox + dx] = hex_to_rgba('#664830')
                grid[oy + 12][ox + dx] = hex_to_rgba('#664830')
            for dy in range(8, 12):
                grid[oy + dy][ox + 2] = hex_to_rgba('#664830')
                grid[oy + dy][ox + 13] = hex_to_rgba('#664830')
        elif otype == 'soc': # SoC outline
            for dx in range(2, 14):
                grid[oy + 2][ox + dx] = hex_to_rgba('#405545')
                grid[oy + 13][ox + dx] = hex_to_rgba('#405545')
            for dy in range(3, 13):
                grid[oy + dy][ox + 2] = hex_to_rgba('#405545')
                grid[oy + dy][ox + 13] = hex_to_rgba('#405545')
            for dx in range(5, 11):
                grid[oy + 5][ox + dx] = hex_to_rgba('#606870')
                grid[oy + 10][ox + dx] = hex_to_rgba('#606870')
            for dy in range(6, 10):
                grid[oy + dy][ox + 5] = hex_to_rgba('#606870')
                grid[oy + dy][ox + 10] = hex_to_rgba('#606870')

        elif otype == 'paper': # Blank paper sheet outline
            for dy in range(2, 14):
                grid[oy + dy][ox + 3] = hex_to_rgba('#dcdde1')
                grid[oy + dy][ox + 12] = hex_to_rgba('#dcdde1')
            for dx in range(3, 13):
                grid[oy + 2][ox + dx] = hex_to_rgba('#dcdde1')
                grid[oy + 13][ox + dx] = hex_to_rgba('#dcdde1')
            grid[oy + 2][ox + 12] = hex_to_rgba('#718093')
            grid[oy + 3][ox + 11] = hex_to_rgba('#718093')
        elif otype == 'writeup': # Blueprint writeup outline
            for dy in range(2, 14):
                grid[oy + dy][ox + 3] = hex_to_rgba('#0984e3')
                grid[oy + dy][ox + 12] = hex_to_rgba('#0984e3')
            for dx in range(3, 13):
                grid[oy + 2][ox + dx] = hex_to_rgba('#0984e3')
                grid[oy + 13][ox + dx] = hex_to_rgba('#0984e3')
            for dx in range(6, 10):
                grid[oy + 6][ox + dx] = hex_to_rgba('#74b9ff')
                grid[oy + 9][ox + dx] = hex_to_rgba('#74b9ff')
            grid[oy + 7][ox + 6] = hex_to_rgba('#74b9ff')
            grid[oy + 8][ox + 6] = hex_to_rgba('#74b9ff')
            grid[oy + 7][ox + 9] = hex_to_rgba('#74b9ff')
            grid[oy + 8][ox + 9] = hex_to_rgba('#74b9ff')

    def draw_energy_bar(bx=9, by=18, bw=12, bh=50):
        c_shadow = hex_to_rgba('#373737')
        c_lit = hex_to_rgba('#ffffff')
        c_inner = hex_to_rgba('#221414')
        # Frame
        for y in range(by, by + bh):
            for x in range(bx, bx + bw):
                if x == bx or y == by:
                    grid[y][x] = c_shadow
                elif x == bx + bw - 1 or y == by + bh - 1:
                    grid[y][x] = c_lit
                else:
                    grid[y][x] = c_inner
        # Lightning icon outline in center
        mid_x = bx + bw // 2
        mid_y = by + bh // 2
        lightning = [
            (0, -3),
            (-1, -2), (0, -2),
            (-2, -1), (-1, -1),
            (-3, 0), (-2, 0), (-1, 0), (0, 0), (1, 0), (2, 0),
            (0, 1), (1, 1),
            (1, 2),
            (1, 3)
        ]
        for ldx, ldy in lightning:
            grid[mid_y + ldy][mid_x + ldx] = hex_to_rgba('#663333')

    # Draw player inventory (3 rows of 9)
    inv_start_y = h - 82
    for row in range(3):
        for col in range(9):
            draw_slot(7 + col * 18, inv_start_y + row * 18)
    # Draw hotbar (1 row of 9)
    hotbar_y = h - 24
    for col in range(9):
        draw_slot(7 + col * 18, hotbar_y)

    return grid, draw_slot, draw_energy_bar

def save_gui(grid, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    byte_data = bytearray()
    for row in grid:
        for p in row:
            byte_data.extend(p)
    cmd = ['ffmpeg', '-y', '-f', 'rawvideo', '-pixel_format', 'rgba', '-video_size', '256x256', '-i', '-', path]
    subprocess.run(cmd, input=byte_data, check=True)

gui_dir = '/home/jvrcruz/IdeaProjects/SiliconCraft/src/main/resources/assets/siliconcraft/textures/gui'

# 1. Chip Forge GUI
g, ds, deb = create_base_gui(176, 166)
deb(9, 18, 12, 50) # Energy bar
ds(26, 49, 'fuel') # Fuel slot (0)
ds(26, 19, 'silicon') # Silicon slot (1)
ds(48, 19, 'redstone') # Redstone slot (2)
ds(48, 49, 'writeup') # Architecture Writeup slot (3)
ds(70, 34, 'chip') # Output chip slot (4)

# Architecture display frame on the right
# x = 94 to 168 (width = 75), y = 16 to 78 (height = 63)
c_shadow = hex_to_rgba('#373737')
c_lit = hex_to_rgba('#ffffff')
c_list_bg = hex_to_rgba('#14181c')
for y in range(16, 79):
    for x in range(94, 169):
        if x == 94 or y == 16:
            g[y][x] = c_shadow
        elif x == 168 or y == 78:
            g[y][x] = c_lit
        else:
            g[y][x] = c_list_bg

save_gui(g, os.path.join(gui_dir, 'chip_forge.png'))

# 2. Memory Forge GUI
g, ds, deb = create_base_gui(176, 166)
deb(9, 18, 12, 50) # Energy bar
ds(26, 49, 'fuel') # Fuel
ds(26, 19, 'silicon') # Silicon
ds(48, 19, 'gold') # Gold
ds(48, 49, 'diamond') # Diamond (NVMe controller)
ds(148, 24, 'chip') # Output slot moved to top right without overlap

# Progress arrow pointing to output slot (x: 124 to 144, y: 30 to 36)
c_shadow = hex_to_rgba('#373737')
c_lit = hex_to_rgba('#ffffff')
c_arrow_bg = hex_to_rgba('#8b8b8b')
for y in range(30, 37):
    for x in range(124, 144):
        # Arrow shaft: 124..137, y=32..34
        # Arrow head: 138..143, slope towards point (143, 33)
        dx = x - 138
        dy = abs(y - 33)
        if (x < 138 and 32 <= y <= 34) or (x >= 138 and dy <= (5 - dx)):
            if x == 124 or dy == (5 - dx) or (x < 138 and (y == 32 or y == 34)):
                g[y][x] = c_shadow
            else:
                g[y][x] = c_arrow_bg

save_gui(g, os.path.join(gui_dir, 'memory_forge.png'))

# 3. Programmer GUI
g, ds, deb = create_base_gui(176, 166)
deb(9, 18, 12, 50) # Energy bar
ds(26, 49, 'fuel') # Fuel
ds(26, 19, 'rom') # ROM input
ds(48, 19, 'rom') # ROM output

# File preview frame on the right
# x = 72 to 168 (width = 96), y = 34 to 62 (height = 28)
c_box_bg = hex_to_rgba('#14181c')
for y in range(34, 63):
    for x in range(72, 169):
        if x == 72 or y == 34:
            g[y][x] = c_shadow
        elif x == 168 or y == 62:
            g[y][x] = c_lit
        else:
            g[y][x] = c_box_bg

save_gui(g, os.path.join(gui_dir, 'programmer.png'))

# 4. SoC Manufacturer GUI
g, ds, deb = create_base_gui(176, 166)
deb(9, 18, 12, 50) # Energy bar
ds(26, 51, 'fuel') # Fuel
ds(26, 13, 'wood') # Wood slab
ds(26, 32, 'silicon') # Silicon powder
ds(46, 13, 'gold') # Gold ingot
ds(46, 32, 'rom') # ROM (BIOS)
ds(46, 51, 'chip') # Silicon chip (CPU)
ds(66, 22, 'ram') # RAM module
ds(66, 42, 'storage') # Storage (NVMe addon)
ds(142, 32, 'soc') # Output SoC
save_gui(g, os.path.join(gui_dir, 'soc_manufacturer.png'))

# 5. Architecture Writer GUI
g, ds, deb = create_base_gui(176, 166)
ds(26, 19, 'paper') # Input paper slot (0)
ds(26, 49, 'writeup') # Output writeup slot (1)

# Downward arrow between slot 0 and slot 1
c_shadow = hex_to_rgba('#373737')
c_lit = hex_to_rgba('#ffffff')
for y in range(38, 48):
    g[y][34] = hex_to_rgba('#505050')
    g[y][35] = hex_to_rgba('#505050')
g[46][33] = hex_to_rgba('#373737')
g[46][36] = hex_to_rgba('#373737')
g[47][34] = hex_to_rgba('#373737')
g[47][35] = hex_to_rgba('#373737')

# Name editbox frame
for y in range(15, 30):
    for x in range(56, 169):
        if x == 56 or y == 15:
            g[y][x] = c_shadow
        elif x == 168 or y == 29:
            g[y][x] = c_lit
        else:
            g[y][x] = hex_to_rgba('#101215')

# Instruction list frame
for y in range(31, 66):
    for x in range(56, 169):
        if x == 56 or y == 31:
            g[y][x] = c_shadow
        elif x == 168 or y == 65:
            g[y][x] = c_lit
        else:
            g[y][x] = hex_to_rgba('#14181c')

# Scrollbar track
for y in range(32, 65):
    g[y][162] = hex_to_rgba('#252a30')
    g[y][167] = hex_to_rgba('#3a4048')

save_gui(g, os.path.join(gui_dir, 'architecture_writer.png'))

print("Updated machine GUIs with energy bars, outlines, and scrollable list frame generated!")
