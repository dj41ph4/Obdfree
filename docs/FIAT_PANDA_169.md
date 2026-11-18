# Fiat Panda II / type 169 (2003–2012) — OBDfree compatibility audit

OBDfree v0.0.6 includes an **opt-in, experimental, read-only Delphi EPS
diagnostic** for Panda 169, including 2008 and 2009 vehicles.

This is NOT full manufacturer coverage and is **not yet verified on a vehicle**.
Engine emission DTCs remain available in the separate Universal OBD-II profile.
Read-only code does not mean guaranteed ECU compatibility.

## Hardware and modules

| Module | Diagnostic port | Cable / protocol | Status in OBDfree |
|---|---|---|---|
| Emissions engine OBD-II | Standard engine line; typically K-line pin 7 on 169 | ELM327, no A1 | Existing universal engine DTCs |
| Delphi electric power steering (EPS) | **Pin 9, K-line** | **Green A1 / compatible K-line ELM327** | Experimental KWP DTC read |
| Bosch ABS 8 / variants | **Pin 1, K-line**, vehicle-dependent | A1 appropriate to pin 1 | Not implemented |
| TRW MY99 airbag | **Pin 3, K-line** | Red A2 and suitable interface | Not implemented |
| Body computer / dash / climate / newer TRW airbag | Variant-specific CAN/K-line | Check vehicle and ECU identity | Not implemented |

The 2008/2009 model year does not identify the exact ECU part number or
protocol session parameters. The official MultiECUScan list distinguishes
engine controller, EPS, ABS, airbag, body and instruments. Generic EOBD alone
cannot establish that the EPS, ABS or airbag is fault-free.

A1 reroutes the ELM K-line from its usual OBD pin 7 to EPS pin 9. Software
cannot change a physical wire on an ordinary ELM dongle. Use the correct
certified and isolated connector; do not casually short diagnostic pins.

## OBDfree flow with A1 adapter (important regression fix)

1. Park the vehicle, immobilize it safely, ignition ON. Do not drive while scanning.
2. **Stop any continuous DTC clearing**. Select the **Panda 169** profile while
   disconnected.
3. Plug the green A1 cable into the OBD socket, then plug the ELM327 into A1.
4. Connect in OBDfree. It now performs **ELM-only handshake** without an engine
   PID 0100 probe. Thus missing engine communication through A1 is expected.
5. Select **Read-only Delphi EPS**. Errors, missing response, malformed frame
   and invalid checksum are reported as errors; never as zero DTCs.
6. For an engine scan: disconnect, remove A1, select **Universal**, reconnect.
   Generic mode (including its separate Mode 04 erase action) is unchanged.

Changing profiles disconnects the transport and requires a fresh handshake.
The EPS profile cannot call generic engine DTC clear or continuous clear.

## Fiat KWP2000 details

- Uses ELM ISO 14230-4 KWP 5-baud initiation (`ATSP4`, `ATSI`) on A1.
- **Provisional** 5-baud initialization target `0x10`, header `80 10 F1`.
  Although documented KWP command forms are confirmed, **no reliable
  Panda-169 Delphi-EPS-specific evidence has confirmed this address**.
- Read request `18 00 FF 00` (Fiat KWP2000 read DTCs by status).
- Positive response `58 <count> [<DTC hi><DTC lo><status>]*` is decoded.
  Status byte is deliberately displayed as raw hex pending exact ECU variant.
- KWP framing is decoded separately from generic OBD-II. Responses from
  foreign ECU sources, malformed lengths, invalid checksums (when shown) and
  negative service response `7F 18 xx` are rejected.
- The KWP session is closed on *all* paths, including unsupported adapter AT
  commands. The ELM is reinitialized **without a generic engine probe**,
  because the A1 cable remains in place.

**No confirmed working ECU address or live-vehicle trace is available yet.**
Do not brute-force addresses or claim a zero-DTC read unless the ECU
acknowledges the request. Do not merge this into `main` until field testing.

## Power-steering fault: avoid premature conclusions

Fiat's 2009 user manual confirms electric Dualdrive and a possible short-lived
thermal protection after repeated parking manoeuvres. Sudden loss of assistance
can also come from voltage drops, power/earth connections, alternator, fuses,
motor, torque sensors and ECU faults. The workshop descriptions mention EPS
power feed **F05**, ignition feed **F24**, and CAN-related speed and alternator
information. Check fuse positions against the exact vehicle documentation
before touching anything.

OBDfree displays the adapter's OBD supply voltage (AT RV), which is **not**
the same as a calibrated, loaded test directly across battery terminals.
Measure battery and alternator independently before attributing the issue
to a steering column.

If the warning stays on or assistance disappears on the road, stop safely
and obtain a professional steering-system diagnosis.

## Validation matrix required before calling it compatible

- Actual 2008 and actual 2009 Panda 169; identify engine, EPS hardware number
  and firmware before declaring a module supported.
- Record exact ELM adapter hardware/chipset; genuine units vs inexpensive clones.
- Verify A1 pinout and electrical isolation **before** plugging in.
- Compare a captured read-only MultiECUScan session with OBDfree's protocol
  (ECU initialization, physical header, timing, service `18`, framing).
- Test known-good ECU, one known EPS fault, no reply, wrong pin, timeout,
  checksum error, negative KWP reply, multiple DTCs and ignition-off.
- Confirm generic engine scan in Universal mode both before and after switching
  A1; verify that EPS mode cannot clear any faults.
- Run Kotlin/Android unit tests, lint and APK build for the final commit.
- Keep a controlled read-only pilot and review logs before changing the
  draft pull request into a production release.

## Technical sources

- Fiat 2009 Panda 169 manual (Dualdrive; thermal protection):
  https://elearneditor.fiat.com/naselearnprod/IT/00/169_PANDA/00_169_PANDA_603.81.647_IT_01_09.09_L_LG/00_169_PANDA_603.81.647_IT_01_09.09_L_LG.pdf
- Fiat Panda electrical system and steering:
  https://4cardata.info/elearn/169/2/2009000/2000900/2001390/2750944
- Fiat Panda factory electrical circuit:
  https://workshop-manuals.com/fiat/panda/wiring_diagrams_and_electrical_functions/vehicle_electronic_systems/electric_steering_operational_description/
- Official MultiECUScan supported systems:
  https://www.multiecuscan.net/SupportedVehiclesList.aspx
- Official AlfaOBD list (independent Fiat ECU coverage cross-check):
  https://www.alfaobd.com/Supported_cars.pdf
- ELM327 AT command reference:
  https://www.scandoc.org/en/develop/elm327.html
- KWP2000 general service definition, ISO 14230-3 (individual ECU exceptions
  can exist): https://www.iso.org/standard/20687.html
