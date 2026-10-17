# MK300 spindle inverter settings

The CNC board only switches the spindle **on/off** (M3 / M5 → X1–COM, see `spindle_setup.md`).
The **speed comes from the MK300 itself**: Hz × 60 = RPM, so **400 Hz = 24000 RPM**.
Manual: `Inverter_Manual-MK300.pdf`. The same list is on the app's **CNC setup** page.

Legend: 🔴 **must be set**, or the spindle will not work or is unsafe · 🟡 **important**, check it · (none) default / informational

## Parameters

| | Parameter | Value | Meaning |
|---|---|---|---|
| 🔴 | **P1-04** | **400.00 Hz** | Rated motor frequency. **Left at 50 Hz, the drive over-volts the 400 Hz spindle and its current limit holds it at about 17 Hz** (slow, humming, hot). Setting this fixed it (2026-10-17) |
| 🟡 | P1-01 | 4.0 kW | Rated motor power (nameplate) |
| 🟡 | P1-02 | nameplate V (220) | Rated motor voltage |
| 🟡 | P1-03 | nameplate A | Rated motor current (about 13 A for 4 kW / 220 V) |
| 🟡 | P1-05 | 24000 rpm | Rated motor speed |
| 🔴 | **P0-10** | **400.00 Hz** | Maximum frequency (factory 50 Hz = 3000 RPM). Set it **first**: P1-04, P0-12 and P0-08 can't be set higher |
| 🔴 | **P0-12** | **400.00 Hz** | Upper limit frequency, which caps every speed |
| 🟡 | P0-11 | 0 | Upper limit from P0-12 (1 / 2 = unwired AI input → about 0 Hz) |
| 🟡 | P0-08 | 400.00 Hz | Preset frequency = spindle speed on M3 |
| 🟡 | P0-03 | 0 or 1 | Speed from panel / P0-08 (2 = AI1; unwired → no speed) |
| | P0-07 | 0 | Main frequency source only (no X + Y mixing) |
| 🔴 | **P0-02** | **1** | Run command from the terminals (0 = panel, which made the spindle run at power-on) |
| 🟡 | P8-18 | 1 | Ignore a run signal that is already on at power-up or fault reset |
| 🟡 | P0-17 | 5–8 s | Acceleration time. The G-code waits 10 s after M3; too short trips the current limit |
| 🟡 | P4-38 | 00001 | X1 logic. **On this unit** 00001 gives short X1–COM = run, open = stop (fail-safe), the reverse of the manual's table. Trust the bare-wire test |
| | P0-01 | 2 | V/F control |
| | P3-00 | 0 | Linear V/F |
| | P3-01 | 0–2 % | Torque boost, keep small (a large boost over-volts at low Hz) |
| | P4-00 | 1 | X1 = forward run (FWD) |
| | P4-11 | 0 | Two-line mode 1 |
| | P6-10 | 0 | Decelerate to stop |
| 🟡 | P7-01 | 1 | JOG (MF) key toggles panel ↔ terminal. The **LOC/REM LED must be on** for X1 to work |

Set the 🔴 frequency parameters in this order: **P0-10 → P0-12 → P1-04 → P0-08**.

## Speed: P0-08 values

| RPM | P0-08 (Hz) |
|---|---|
| 24000 | 400.0 |
| 20000 | 333.3 |
| 18000 | 300.0 |
| 15000 | 250.0 |
| 12000 | 200.0 (don't go lower for cutting: air-cooled spindles lose torque and run hot) |

The G-code `S` word does **not** set the speed: only on/off is wired. Making S control the speed would need
the board's PWM output (GPIO27 on FluidNC) driving AI1 through a PWM → 0–10 V converter, plus P0-03 = 2.

## Keypad

1. Spindle stopped. **PRG** → the group (P0, P1…) → **ENTER**.
2. **▲▼** to the parameter → **ENTER**.
3. Change the value with **▲▼**; **◄ / SHIFT** moves between digits.
4. **ENTER** saves; **PRG** leaves the menu.

The stopped display blinks the set frequency (e.g. 400.00).

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Spindle stuck at about 17 Hz, slow and humming | 🔴 P1-04 still 50 Hz (motor nameplate not set) → current limit. Set P1-01…P1-05 |
| Stuck at 50 Hz (3000 RPM) | 🔴 P0-10 / P0-12 still 50 Hz |
| Runs at about 0 Hz | P0-03 = 2 or P0-11 = 1/2 (unwired AI input) |
| M3 does nothing | LOC/REM LED off → press JOG (P7-01 = 1); P0-02 must be 1 |
| Starts at power-on | P0-02 = 0 or P8-18 = 0 |

**Check while running:** ►► / SHIFT cycles the running display (Hz, V, A). If it is stuck far below the
preset with the current at the rated value, the current limit is holding it → recheck P1-04 and the other P1 values.
