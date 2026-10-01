# Spindle control: Grbl (Pro Mini) → MK300 VFD

Grbl switches the spindle on and off with **M3 / M5**. Speed is still set on the
MK300 panel (▲▼ / P0-08). Source: *Inverter_Manual-MK300.pdf*, pages 28–36 (terminals and wiring),
41 (P0-02), 67 and 72 (P4-00, P4-11), 97–98 (P8-18).

## How it works

```
M3 S20000 ──► Grbl drives D13 HIGH ──► PC817 conducts ──► X1 shorted to COM ──► MK300 FWD RUN
M5 / reset / alarm / USB loss ──► D13 LOW ──► X1 open ──► MK300 decelerates to stop
```

- Stock Grbl 1.1h (variable spindle) has **no on/off pin**: M3/M5 only change the PWM on D11
  (D12 is the Z limit, D13 is direction). The firmware in `firmware/` is built with
  `USE_SPINDLE_DIR_AS_ENABLE_PIN`, so **D13 is the spindle enable**: HIGH on M3, LOW on M5, soft
  reset, alarm and power-up. D11 remains PWM for a future 0–10 V speed signal.
- The Pro Mini's on-board LED is on D13, so it lights whenever the spindle is commanded on.
- **Fail-safe:** an Arduino reset, a USB disconnect or a Grbl alarm drops D13, and the spindle stops.

## Wiring

The MK300 inputs X1–X6 are opto-isolated, 9–30 V, with about a 2 kΩ input. X1 sits at about
+24 V, so **never connect the 3.3 V Pro Mini to it directly.** Use an optocoupler:

```
Pro Mini D13 ──[150 Ω]──► PC817 pin 1 (anode)
Pro Mini GND ───────────► PC817 pin 2 (cathode)
PC817 pin 4 (collector) ─► MK300 X1
PC817 pin 3 (emitter)  ──► MK300 COM
```

- Keep the MK300's factory **SEL–+24 link** ("Connection method 1", manual p.33). The inverter's
  own +24 V then powers the input, and closing X1 to COM turns it on.
- 150 Ω gives about 14 mA LED current from 3.3 V (pin current about 15 mA including the on-board
  LED, within the ATmega's limits). The X1 input needs about 11 mA. If X1 does not switch, use a
  higher-CTR PC817 (rank B/C) or a ready-made 3.3 V optocoupler isolation module.
- Keep **Arduino GND and MK300 COM separate.** The opto is the isolation barrier against VFD noise.

## MK300 parameters

| Parameter | Value | Meaning |
|---|---|---|
| P0-01 | 2 | V/F control (already set) |
| **P0-02** | **1** | Run command from the terminals (was 0 = panel, which made the spindle run at power-on) |
| P0-03 / P0-08 | unchanged | Frequency (speed) from panel / preset frequency |
| P4-11 | 0 (default) | Two-line mode 1 |
| P4-00 | 1 (default) | X1 = Forward RUN (FWD) |
| **P8-18** | **1** | Startup protection: ignore a run signal already on at VFD power-up or fault reset |
| P6-10 | 0 (default) | Decelerate to stop |
| P0-17 | ≤ about 8 s | Acceleration time; the G-code waits 10 s after M3 (adjust `--spinup` if longer) |

## Grbl settings

```
$30=20000   max spindle rpm (S20000 = 100 %)
$31=0       min spindle rpm
$32=0       laser mode off (laser mode would turn the spindle off between moves)
```

## G-code

`cam/dome_gcode.py --spindle vfd` (the default) emits:

```
M0                 (check the stack is clamped and Z0 is set, then press Resume to start the spindle)
M3 S20000          (spindle ON)
G4 P10             (wait for the spindle to reach speed)
… cutting …
G0 Z5
M5                 (spindle OFF)
G0 X0 Y0
M2
```

`--rpm` sets the S word (keep it equal to `$30`). `--spinup` sets the dwell. `--spindle manual` restores
the hand-switched M0 pauses.

## Flashing the firmware

Arduino on this PC's USB (`/dev/ttyUSB0`); `-D` keeps the bootloader. `$` settings live in EEPROM
and survive the flash.

```sh
~/.arduino15/packages/arduino/tools/avrdude/8.0.0-arduino1/bin/avrdude \
  -C ~/.arduino15/packages/arduino/tools/avrdude/8.0.0-arduino1/etc/avrdude.conf \
  -p m328p -c arduino -P /dev/ttyUSB0 -b 57600 -D \
  -U flash:w:firmware/grbl-1.1h-promini-8mhz-38400.hex:i
```

To rebuild: apply `firmware/grbl-1.1h-promini-8mhz-38400.patch` to gnea/grbl, then run `make` with Arduino's
avr-gcc on PATH (`~/.arduino15/packages/arduino/tools/avr-gcc/7.3.0-atmel3.6.1-arduino7/bin`).
Size: 29,690 / 30,720 bytes.

## Test checklist

1. Flash the firmware and set `$30=20000`.
2. Wire the opto. Set P0-02=1 and P8-18=1 on the MK300.
3. Power the VFD: the spindle must **not** start.
4. In Grbl Sender, send `M3 S20000`: the D13 LED lights and the spindle starts.
5. Send `M5`: the spindle decelerates to stop.
6. `M3`, then tap **Reset**: the spindle stops. `M3`, then unplug USB: the spindle stops.
7. Re-download `dome_parts_30mm_8mm_F2000.nc` on the tablet (the version with M3/M5).

## (B) Blue Pill (STM32F103C8T6)

Same optocoupler wiring, from a different pin:
- **64 KB board (this machine), Grbl 1.1f:** spindle enable on **PB13** (PWM PA8, direction PB14).
- 128 KB board, grblHAL: spindle enable on PB1 (PWM PA8, direction PB0).

M3/M5, reset and alarm behave the same. Outputs are 3.3 V like the Pro Mini.
Pin maps: `firmware/bluepill/BUILD.md`.
