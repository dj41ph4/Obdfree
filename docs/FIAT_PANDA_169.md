# Fiat Panda II / type 169 (2003–2012) — diagnostic scope

## Objective

Give OBDfree a **separate, opt-in Fiat Panda 169 profile** without changing generic
OBD-II behavior on other vehicles. Particularly relevant to 2008 and 2009 models
with the red steering-wheel warning light and sudden heavy steering.

**This is an experimental read-only EPS implementation, not a verified full-vehicle
scanner. Hardware and ECU variant testing are still required.**

## Verified hardware boundaries

| System | Physical bus / OBD pin | Adapter | OBDfree implementation |
|---|---|---|---|
| Emission-related engine fault codes | Standard OBD-II port | ELM327 | Existing generic Mode 03/07/0A, Mode 04 |
| Delphi electric power steering (EPS) | K-line, **pin 9** | K-line-capable ELM327 + **A1 green** | **Experimental** read-only Fiat KWP2000 code request |
| Bosch ABS 8 | K-line, **pin 1** | Adapter appropriate to pin 1 | Not yet implemented |
| TRW MY99 airbag | K-line, **pin 3** | **A2 red**; suitable diagnostic interface | Not yet implemented |
| Body computer / instruments / other modules | Variant-specific | Variant-specific | Not yet implemented |

The pin and adapter references are drawn from MultiECUScan compatibility tables;
they are **not** a claim that all ECUs and engines are identical. A generic ELM327
cannot electronically move its K-line from the default OBD pin 7 to pin 9:
the A1 cable or equivalent properly wired interface is needed.

## Experimental EPS sequence

- Profile is disabled by default; select **Panda 169** explicitly.
- Only usable with an active, initialized ELM connection, no concurrent scan
  and automatic fault clearing disabled.
- Session uses ELM ISO 14230-4 5-baud K-line mode, fixed candidate init address
  0x10 and tester header `80 10 F1`.
- Request `18 00 FF 00` (Fiat KWP2000 `readDiagnosticTroubleCodesByStatus`);
  accept only confirmed `58 count (DTC-hi, DTC-lo, status)*` responses.
- Status byte shown verbatim in hex; no unverified code-description mappings.
- KWP session is closed and generic ELM initialization attempted on exit.
- No actuator activation, DTC erase, calibration, programming, PROXI alignment,
  airbag action or high-risk function is sent.

**Remaining unknown:** whether the Delphi EPS variant fitted to an individual
2008/2009 Panda accepts the above initialization address and header.
If not, the software reports an error instead of reporting “zero faults”.
Do not brute-force ECU addresses in a safety-related controller.

## On-car validation plan

1. Record Fiat Panda 169 exact variant, engine and EPS part number.
2. Connect with ignition ON, vehicle parked and engine off; verify power supply.
3. Read generic engine codes and record successful response.
4. Insert an appropriate A1 green adapter between the ELM327 and vehicle port.
5. Select **Panda 169** and read EPS. Record both the displayed raw reply and any error.
6. Cross-check DTCs and ECU identification with licensed MultiECUScan or an
   independent Fiat-capable workshop scanner. Compare on a known-fault vehicle.
7. Confirm generic scan continues to work after exiting the Fiat profile.
8. Test both a genuine ELM-compatible adapter and any specific clone models
   that should be supported. Unsupported commands must produce explicit errors.

Never assume that an empty generic engine scan clears the EPS or ABS system.
Unassisted/heavy steering is a driving safety concern: stop safely and have
the electrical supply, fuses, column and DTCs checked before normal use.

## References

- MultiECUScan official supported vehicles: https://www.multiecuscan.net/SupportedVehiclesList.aspx
- Fiat Panda (169) electric steering description:
  https://4cardata.info/elearn/169/2/2009000/2000900/2001390/2750944
- KWP2000 and ELM AT command reference:
  https://www.scandoc.org/en/develop/elm327.html
- ISO 14230-3 (KWP2000) service 0x18 / 0x58 reference:
  https://standards.theiteh.com/catalog/standards/iso/20c9a21d-a095-41d7-81fa-423494aa28e7/iso-14230-3-1999
