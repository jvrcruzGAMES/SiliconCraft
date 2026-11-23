; Connect an Environment Detector to port 2 (north).
PWRAP A, 2
CALL wait_request
PRESULT C
PCALL A, 2, 0x420, 0, 0
CALL wait_request
PLEN B
CMP B, 255
JG failed
PRESULTS C, 0x200, 256
MOV D, 0x200
print_biome:
CMP C, 0
JZ done
OUTC [D]
INC D
DEC C
JMP print_biome
done:
OUTC 10
HLT

wait_request:
CMP A, 1
JNZ failed
wait_pending:
PSTATUS B
CMP B, 2
JZ ready
CMP B, 1
JNZ failed
SLEEP 1
JMP wait_pending
ready:
RET

failed:
PRESULT C
OUT "Environment Detector request failed."
HLT
