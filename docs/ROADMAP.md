# OBDfree roadmap

## 0.1 — Universal core

- Bluetooth Classic paired-device connection
- ELM327 initialization
- Generic DTC read/clear
- RPM / speed / coolant / throttle
- premium glass UI
- CI debug APK

## 0.2 — Adapter quality

- BLE transport
- ELM/STN capability fingerprint
- clone detection / command fallback
- timeout profiles
- reconnect
- raw terminal diagnostic screen

## 0.3 — Complete generic OBD-II

- supported PID discovery (0100/0120/0140...)
- freeze frame
- readiness
- O2 / wideband data
- Mode 06 monitor results
- VIN / calibration IDs
- distance/time since MIL
- fuel trims
- battery / adapter voltage

## 0.4 — Data experience

- 10–20 Hz scheduler where adapter/ECU allows it
- graph cards
- dashboard editor
- trip sessions
- CSV export
- local history
- compare scans

## 0.5 — Knowledge layer

- offline DTC knowledge database
- probable causes ranked separately from confirmed facts
- freeze-frame contextual hints
- repair notes
- no diagnosis presented as certainty without evidence

## 1.0 — Production

- adapter compatibility matrix
- physical-vehicle test suite
- Play Store packaging
- signed reproducible releases
- accessibility
- translations
- privacy/security review
