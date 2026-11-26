; Prints a sample each second. Press Start (or Enter) to stop.
; 0.1.2: enable INAXIS on the chip and connect a controller through Controlify.
poll:
INPAD D, 0
AND D, 0x0010
JNZ done
INAXIS A, 0, 0
OUT "Left stick X: ", A
INAXIS A, 0, 1
OUT "Left stick Y: ", A
INAXIS A, 0, 2
OUT "Right stick X: ", A
INAXIS A, 0, 3
OUT "Right stick Y: ", A
INAXIS A, 0, 4
OUT "Left trigger: ", A
INAXIS A, 0, 5
OUT "Right trigger: ", A
INPAD B, 0
MOV C, B
AND C, 0x4000
OUT "Left stick button mask: ", C
AND B, 0x8000
OUT "Right stick button mask: ", B
SLEEP 20
JMP poll
done:
HLT
