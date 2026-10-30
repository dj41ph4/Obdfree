# OBDfree architecture

## Design goals

1. Keep transport concerns separate from OBD-II parsing.
2. Never make UI code speak ELM327 commands directly.
3. Treat cheap ELM327 clones as unreliable I/O endpoints: timeouts and malformed replies are expected.
4. Keep destructive commands explicit and user-confirmed.
5. Do not imply that generic OBD-II gives universal ABS/SRS/body access.

## Layers

### Transport

`ObdTransport` is byte-oriented.

Current implementation:
- Bluetooth Classic RFCOMM / SPP

Planned:
- BLE
- Wi-Fi TCP
- USB serial

### ELM327 session

`Elm327Client` owns:
- prompt-based request/response handling
- adapter initialization
- Mode 03
- Mode 04
- selected Mode 01 PIDs

### Decoder

`ObdDecoder` owns:
- DTC P/C/B/U bit decoding
- PID formulas
- common-code descriptions

It is intentionally stateless and testable.

### UI

Compose observes `DiagnosticUiState`. UI events are routed through `ObdViewModel`.

## Safety model

Mode 04 is only exposed behind a confirmation dialog.

Future manufacturer-specific write/coding functions must use a stricter capability model:
- explicit ECU identification
- vehicle compatibility declaration
- action-specific confirmation
- no blind broadcast writes
