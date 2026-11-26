; Connect a Digital Explorer's Compass to port 2 (north).
PWRAP A, 2
CALL wait_request
PRESULT C
PCALLS A, 2, 0x490, 0x400, 256
CALL wait_request
PLEN B
CMP B, 0
JZ numeric_result
CALL print_result
HLT
numeric_result:
PRESULT C
OUT C
HLT

print_result:
PLEN B
CMP B, 255
JG failed
PRESULTS C, 0x200, 256
MOV D, 0x200
print_text:
CMP C, 0
JZ done
OUTC [D]
INC D
DEC C
JMP print_text
done:
OUTC 10
RET

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

ORG 0x400
DB "minecraft:village_plains", 0
