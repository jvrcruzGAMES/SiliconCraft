; Airship Sensor north (2), Digital Redstone Link east (5).
PWRAP A, 2
CALL finish_request
CMP C, 1
JNZ failed

PCALL A, 2, 0x300, 0, 0
CALL finish_request
OUT "Altitude (thousandths of a block):"
OUT C

PWRAP A, 5
CALL finish_request
CMP C, 1
JNZ failed

PCALLS A, 5, 0x310, 0x200, 1
CALL finish_request
CMP C, 1
JNZ failed

SLEEP 20

PCALLS A, 5, 0x310, 0x200, 0
CALL finish_request
CMP C, 0
JNZ failed
OUT "Engine is off."
HLT

; A holds the acceptance flag. C receives the completed result.
finish_request:
CMP A, 1
JNZ failed
wait_for_server:
PSTATUS B
CMP B, 1
JNZ request_complete
SLEEP 1
JMP wait_for_server
request_complete:
PRESULT C
CMP B, 2
JNZ failed
RET

failed:
OUT "Airship request failed. Check ports and frequency."
HLT

ORG 0x200
DB "Engine", 0
