# OBDfree

OBDfree is a free, privacy-first Android OBD-II diagnostic app built with Kotlin and Jetpack Compose.

## Product direction

- Premium glass UI designed for fast use in a car.
- Bluetooth Classic ELM327 support first, with a transport abstraction ready for BLE/Wi-Fi later.
- Read generic OBD-II diagnostic trouble codes (DTCs).
- Clear emission-related DTCs with an explicit safety confirmation.
- Live-data foundation for standard Mode 01 PIDs.
- No account, no cloud dependency, no ads, no telemetry by default.
- Clear separation between generic OBD-II and manufacturer-specific diagnostics.

## V0.1 scope

1. Request the Android Bluetooth permission.
2. List already-paired Bluetooth adapters.
3. Connect over SPP to an ELM327-compatible adapter.
4. Initialize the adapter (`ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH0`, `ATAT1`, `ATSP0`).
5. Read DTCs with OBD-II Mode 03.
6. Decode P/C/B/U codes.
7. Clear emission-related diagnostic information with Mode 04 after confirmation.
8. Read basic live data: RPM, speed, coolant temperature and throttle position.

## Diagnostic boundary

Generic OBD-II primarily exposes emission-related powertrain diagnostics. ABS, SRS/airbag, body, coding, adaptations and service functions are manufacturer-specific and are **not** claimed as universal features.

Clearing codes does not repair a fault. Mode 04 can also clear freeze-frame information and reset readiness monitors. OBDfree therefore requires an explicit confirmation before sending it.

## Architecture

```text
app/src/main/java/com/obdfree/app/
  core/obd/       ELM327 protocol and OBD decoders
  core/transport/ Transport interface + Bluetooth Classic implementation
  model/          Diagnostic and live-data models
  ui/             Compose UI and glass design system
```

The OBD engine does not depend on Compose. BLE, Wi-Fi or USB transports can be added without rewriting the diagnostic layer.

## Toolchain

- Android 17 / API 37
- Android Gradle Plugin 9.4.1
- Gradle 9.6
- Jetpack Compose Material 3

## Run

Open the project in a recent Android Studio with API 37 installed.

The repository intentionally does not include the binary `gradle-wrapper.jar` in this bootstrap. Android Studio can sync the project directly; to generate a local wrapper, run:

```bash
gradle wrapper --gradle-version 9.6.0
```

## Roadmap

- [x] Project foundation
- [x] Premium glass design system
- [x] Bluetooth Classic transport
- [x] ELM327 command channel
- [x] Mode 03 DTC reading
- [x] P/C/B/U DTC decoding
- [x] Mode 04 clearing with confirmation
- [x] Basic Mode 01 live PIDs
- [ ] Real-adapter validation matrix
- [ ] BLE transport
- [ ] Automatic adapter capability profiling
- [ ] Freeze-frame Mode 02
- [ ] Readiness / Mode 06
- [ ] VIN / Mode 09
- [ ] Persistent scan history
- [ ] CSV logging and graphing
- [ ] Manufacturer plug-in layer
- [ ] Signed APK release pipeline

## License

A license will be selected before the first public release.
