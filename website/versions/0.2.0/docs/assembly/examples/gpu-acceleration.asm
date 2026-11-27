; ============================================
; Program: gpu-acceleration.asm
; Description: Detects GPU, selects handler, draws hardware-accelerated
;              shapes and VRAM sprites on VirtualGpu
; ============================================

    GFXSTAT A, 0          ; 1 if GPU is installed
    CMP A, 0
    JZ cpu_fallback

    OUT "GPU detected! Using VirtualGpu acceleration."
    GFXSEL 0              ; AUTO: route FB* to GPU automatically
    FBMODE 1

    GPUCLR 0xFF101824     ; Dark blue-slate backdrop
    GPURECT 40, 40, 220, 140, 0xFF2255AA
    GPUCIRC 480, 270, 90, 0xFF2EF0BB, 1
    GPUCIRC 480, 270, 95, 0xFFFFFFFF, 0
    GPULINE 40, 40, 920, 500, 0xFFFFCC00

    ; Triangle vertex descriptor in RAM[512..518]: x0, y0, x1, y1, x2, y2, color
    MOV [512], 700
    MOV [513], 120
    MOV [514], 840
    MOV [515], 320
    MOV [516], 580
    MOV [517], 320
    MOV [518], 0xFFFF4466
    GPUTRI 512

    ; Upload 2x2 sprite from RAM[520..523] into VRAM[0] and alpha-blit it
    MOV [520], 0xFF00FFFF
    MOV [521], 0x00000000
    MOV [522], 0x00000000
    MOV [523], 0xFFFF00FF
    GPUVRAM 0, 520, 4
    GPUBLIT 479, 269, 0, 2, 2

    GPUSWAP
    GPUSYNC
    HLT

cpu_fallback:
    OUT "No GPU installed; falling back to CPU framebuffer."
    GFXSEL 1              ; CPU software framebuffer
    FBMODE 1
    FBCLR 0xFF101824
    FBRECT 40, 40, 220, 140, 0xFFCC4444
    FBSWAP
    HLT
