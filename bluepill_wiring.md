# Blue Pill wiring: Grbl 1.1f → RichAuto breakout board → MA860H drivers, MK300 VFD, switches

How to wire the Blue Pill (STM32F103C8T6, 64 KB) running
`firmware/bluepill/grbl-1.1f-bluepill64-maple.bin` to the router. The F103 Black Pill uses the same
pins (see the PB12 note under limits).

Sources: `grbl-stm32-classic/inc/cpu_map.h` (`ABC_AXIS_EXAMPLE` block, which `inc/config.h` selects, with
`AA_AXIS` on), `grbl-stm32-classic/STM32F103-USBCNC-Pinout.png`, *MA860H-stepper-motor-driver.pdf* (control signal
interface), *Inverter_Manual-MK300.pdf* (pages 28–36, P0-03, P4-13…P4-16), `spindle_setup.md`, the RichAuto
breakout board (`RichAuto_IOboard_1B-A1X-10.png`, 50-pin connector pinout measured on the board).

> **Pin map warning.** `README.md`, `firmware/bluepill/BUILD.md` and `spindle_setup.md` list the
> `CPU_MAP_STM32F103` pins (step PA0–2, dir PA3–5, enable PA6, limits PB10–12, spindle PB13, probe PA15).
> **That map is not compiled in.** `config.h` defines `ABC_AXIS_EXAMPLE` and leaves `CPU_MAP_STM32F103`
> commented out, so the flashed image uses the pins below. Wiring to the old table would put X direction
> on Y step, and so on.

## 1. Pin map (flashed firmware)

| Function | Pin | Board edge (USB at top) | 5 V tolerant | Notes |
|---|---|---|---|---|
| X step | **PA0** | right | no | RichAuto pin 2 |
| Y step | **PA1** | right | no | RichAuto pin 4 |
| Z step | **PA2** | right | no | RichAuto pin 6 |
| A step | PA3 | right | no | 4th axis, unused on this machine |
| X direction | **PA4** | right | no | RichAuto pin 3 |
| Y direction | **PA5** | right | no | RichAuto pin 5 |
| Z direction | **PA6** | right | no | RichAuto pin 7 |
| A direction | PA7 | right | no | unused |
| Stepper disable | PA15 | left | yes | HIGH = disabled with `$4=0`; not on the RichAuto connector, unused with `$1=255` |
| X limit | **PB12** | left (1st pin) | yes | internal pull-up, switch to GND |
| Y limit | **PB13** | left | yes | internal pull-up |
| Z limit | **PB14** | left | yes | internal pull-up |
| A limit | PB15 | left | yes | unused; `$5` bit 3 |
| Spindle enable (MK300 X1) | **PB1** | right | no | HIGH on M3, LOW on M5 / reset / alarm; RichAuto pin 29 (Y1) |
| Spindle direction | PB0 | right | no | HIGH on M4 (not needed, the VFD runs forward only) |
| Spindle PWM | PA8 | left | yes | TIM1 CH1, **10 kHz**, 0–3.3 V |
| Coolant flood / mist | PB4 / PB3 | left | yes | M8 / M7, e.g. dust extractor relay; RichAuto pin 22 (Y2) / 28 (Y3) |
| Reset / feed hold / cycle start | PB5 / PB6 / PB7 | left | PB5 no; PB6, PB7 yes | button to GND |
| Safety door | PB8 | left | yes | switch to GND |
| Probe | **PB9** | left | yes | touch plate to PB9, bit clip to GND |
| USB D− / D+ | PA11 / PA12 | | | used by the tablet link, do not connect |
| SWD | PA13 / PA14 | 4-pin end header | | only for the bootloader; Grbl turns SWD off |

Physical order, USB socket at the top:

```
          left header                      right header
  PB12  X limit                         GND
  PB13  Y limit                         GND
  PB14  Z limit                         3V3
  PB15  (A limit)                       NRST
  PA8   spindle PWM                     PB11
  PA9   —                               PB10
  PA10  —                               PB1   spindle enable ──► RA 29 (Y1)
  PA11  USB D−                          PB0   (spindle dir)
  PA12  USB D+                          PA7   (A dir)
  PA15  (stepper disable)               PA6   Z dir ──► RA 7
  PB3   (mist) ──► RA 28 (Y3)           PA5   Y dir ──► RA 5
  PB4   (flood) ──► RA 22 (Y2)          PA4   X dir ──► RA 3
  PB5   reset button                    PA3   (A step)
  PB6   feed hold button                PA2   Z step ──► RA 6
  PB7   cycle start button              PA1   Y step ──► RA 4
  PB8   door switch                     PA0   X step ──► RA 2
  PB9   probe                           PC15
  5V    (USB 5 V, nothing else)         PC14
  GND   ──► RA GND (26, 27, 30 …)       PC13  on-board LED
  3V3   ──► 1k/1k divider (1.6 V)       VBAT
```

"RA n" = pin n of the RichAuto board's 50-pin connector (section 3).

All inputs use the STM32's internal pull-up (~40 kΩ), so every switch closes to GND. All outputs are
3.3 V push-pull. Keep the load to ~8 mA per pin (20 mA absolute) and 150 mA for the whole chip.

## 2. Overview

```
 Tablet ──USB OTG── Blue Pill ──3.3 V──► RichAuto breakout board (24 V in, own 5 V)
                       │                   ├── 26LS32 (5 V out) ──► MA860H X, Y, Z   (PUL/DIR, existing cables)
                       │                   └── ULN2803 Y1 ──► PC817 ──► MK300 X1 / COM   (spindle on/off)
                       ├── PB12–PB14 ◄── limit switches (to GND, straight to the Blue Pill)
                       ├── PB5–PB9   ◄── buttons, door, probe (to GND, straight to the Blue Pill)
                       └── PA8 (optional) ──► PWM→0–10 V module ──► MK300 AI1 / GND   (spindle speed)
```

The MA860H inputs are optocouplers made for 5 V signals ("VCC is 5V, R is short"). Driven straight from 3.3 V,
their LEDs get only about half the current. That often works on the bench but can drop steps with long cables
and VFD noise. A 5 V stage fixes that and also protects the Blue Pill. The recycled RichAuto board already has
one (26LS32 receivers) and is already cabled to the drivers, so it is the interface. The 74ACT245 buffer
(section 3.2) is the fallback if the board is not available.

## 3. Interface board

### 3.1 RichAuto breakout board (26LS32) — used

The recycled RichAuto I/O board (`RichAuto_IOboard_1B-A1X-10.png`) runs on 24 V and makes its own 5 V with an
on-board step-down converter. Its 50-pin controller connector carries:

- **Step and dir** as differential pairs into 26LS32 line receivers. The 26LS32 outputs (5 V) go to the driver
  connectors, so this board does the 3.3 V → 5 V shift. The cables from these connectors to the MA860H drivers
  stay as the RichAuto installation left them.
- **Outputs Y1–Y8** as inputs of a ULN2803 (open-collector, sinks to board GND).
- **Inputs X1–X8** serialized by an HVS885 and sent as differential CLK / LD / SOP. Grbl can't read that.

#### 50-pin connector

| Pin | Signal | Blue Pill |
|---|---|---|
| 2 / 49 | X pulse + / − | **PA0** → 2, divider → 49 |
| 3 / 48 | X dir + / − | **PA4** → 3, divider → 48 |
| 4 / 47 | Y pulse + / − | **PA1** → 4, divider → 47 |
| 5 / 46 | Y dir + / − | **PA5** → 5, divider → 46 |
| 6 / 45 | Z pulse + / − | **PA2** → 6, divider → 45 |
| 7 / 44 | Z dir + / − | **PA6** → 7, divider → 44 |
| 8 / 43 | C pulse + / − | (PA3, A step, unused) |
| 9 / 42 | C dir + / − | (PA7, A dir, unused) |
| 13 / 38 | HVS885 CLK − / + | not used |
| 14 / 35 | HVS885 LD + / − | not used |
| 15 / 33 | HVS885 SOP − / + | not used |
| 29 | Y1 (ULN2803 input) | e.g. **PB1** spindle enable, see below |
| 22 | Y2 | e.g. PB4 flood (dust extractor) |
| 28 | Y3 | e.g. PB3 mist |
| 23, 24, 25, 19, 32 | Y4, Y5, Y6, Y7, Y8 | free |
| 1 | 5 V out (board supply) | **don't connect** to the Blue Pill 5V pin |
| 26, 27, 30, 31, 34, 36, 50 | GND | Blue Pill **GND** (at least two pins) |

The pin list as given marks 45, 44 and 43 as "+" and 42 as "C pulse −". By the pattern of the rest these are
Z pulse −, Z dir −, C pulse − and C dir −. Check with a meter before wiring.

#### Step and dir: + pin from the Blue Pill, − pin at 1.6 V

A 26LS32 output is HIGH when its A (+) input is more than 0.2 V above B (−), and LOW when more than 0.2 V below.
The Blue Pill gives a single-ended 0 / 3.3 V signal. Wire it to the **+** pin, and hold every **−** pin at about
1.6 V from one shared divider:

```
                     3V3
                      │
                     1k
                      │
Blue Pill PA0 ────────┼─────────────► pin 2  (X pulse +)
                      │
             ~1.6 V ──┴─────────────► pin 49 (X pulse −), and 48, 47, 46, 45, 44
                     1k
                      │
Blue Pill GND ───────GND────────────► pins 26, 27, 30 … (board GND, must be shared)
```

- PA0 at 0 V gives +/− difference −1.6 V (output LOW). At 3.3 V it gives +1.7 V (output HIGH). Both are far past
  the ±0.2 V threshold and well inside the ±7 V common-mode range.
- **Keep the divider at about 1 k / 1 k.** Each 26LS32 input is only about 12 kΩ, and six of them hang on the
  divider. A 10 k divider would sag.
- **Don't tie the − pins to GND.** With PA0 low, both inputs would sit at 0 V, inside the undefined zone, and the
  output could chatter.
- If a pulse or direction comes out inverted on the driver side, fix it with `$2` (step invert) / `$3` (dir invert).
- **Check how the 26LS32 outputs drive the MA860H.** A 26LS32 output sinks about 8 mA but sources only about
  0.4 mA. That works if the board wires the opto as common-anode (PUL+ to +5 V, PUL− to the 26LS32 output), which
  makes the driver input active-LOW. If the output drives PUL+ directly, the opto LED gets too little current.
  Trace one channel before relying on it.
- **Enable (PA15):** the connector has no enable line. Leave ENA+/ENA− on the drivers unconnected (drivers always
  enabled) and keep `$1=255`. PA15 stays unused.

#### Outputs Y1–Y8 (ULN2803)

- A Blue Pill pin can drive a ULN2803 input directly. The ULN2803 has a 2.7 kΩ base resistor, and 3.3 V is enough
  for relay coils and lamps (up to about 200 mA per channel).
- The ULN2803 inverts: Blue Pill HIGH → Y output pulled to board GND (on). Grbl's spindle and coolant pins are
  HIGH when on, so no invert setting is needed.
- **Spindle enable via Y1:** Y1 lights a PC817 that closes MK300 X1 (section 5). Y1 could also drive X1 directly
  (board GND → MK300 COM), which is probably how the RichAuto controller did it, but that joins the VFD COM to the
  logic GND and the tablet. The PC817 keeps them apart.
- During boot PB1 floats. Add a **10 kΩ pull-down from PB1 to GND** so Y1 stays off.

#### Inputs X1–X8: not usable

X1–X8 reach the controller only as an HVS885 serial stream (CLK / LD / SOP). Grbl reads limit switches, probe and
buttons as plain pins, so wire them straight to the Blue Pill as in sections 6 and 7, not to the board's X terminals.

#### Power

- The board makes its own 5 V from 24 V. Pin 1 is that 5 V. Don't connect it to the Blue Pill's 5V pin while the
  tablet's USB is plugged in: two 5 V supplies would feed each other.
- Do connect the grounds (Blue Pill GND to the connector GND pins). The 26LS32 compares against it.
- The board needs its 24 V supply on before anything moves. With the board unpowered, the 26LS32 and ULN2803
  outputs are dead: the drivers see no pulses and the spindle stays off.

### 3.2 Fallback: own buffer board (74ACT245)

One 74ACT245 has 8 channels: exactly X/Y/Z step, X/Y/Z dir, ENA and spindle enable.
Use the **ACT** (or AHCT) family: its inputs read 3.3 V as HIGH at 5 V supply (V<sub>IH</sub> = 2.0 V),
and it drives ±24 mA. Plain 74HC at 5 V needs 3.5 V for a HIGH, so it won't work.

| 74ACT245 pin | Connect to |
|---|---|
| 20 VCC | Blue Pill **5V** pin (tablet USB 5 V) + 100 nF to GND at the chip |
| 10 GND | Blue Pill GND |
| 1 DIR | VCC (A → B) |
| 19 /OE | GND (always on) |
| 2 A1 → 18 B1 | PA0 → X PUL+ |
| 3 A2 → 17 B2 | PA4 → X DIR+ |
| 4 A3 → 16 B3 | PA1 → Y PUL+ |
| 5 A4 → 15 B4 | PA5 → Y DIR+ |
| 6 A5 → 14 B5 | PA2 → Z PUL+ |
| 7 A6 → 13 B6 | PA6 → Z DIR+ |
| 8 A7 → 12 B7 | PA15 → ENA+ of all three drivers |
| 9 A8 → 11 B8 | PB1 → PC817 anode via 330 Ω (spindle) |

- **10 kΩ pull-down from A8 (PB1) to GND.** While the Blue Pill boots or is being flashed, PB1 floats.
  The pull-down keeps the spindle off.
- **No pull-down on A7 (PA15).** PA15 (JTDI) has an internal pull-up at reset, so the drivers stay disabled
  (motors free) until Grbl starts. Step and dir lines can float during boot. The drivers are disabled then,
  so stray pulses do nothing.
- Powering the buffer from the Blue Pill's 5 V is a deliberate fail-safe: unplug the USB cable and every output,
  spindle included, goes off.
- Use twisted pairs or shielded cable to the drivers (PUL+ with PUL−, DIR+ with DIR−), and keep them away from
  the motor and VFD output cables.

## 4. MA860H stepper drivers (X, Y, Z)

**With the RichAuto board** the drivers stay on the board's driver connectors, cabled as the RichAuto
installation left them. Nothing changes on the driver side except the DIP switches below. The table that follows
is for the 74ACT245 fallback only.

74ACT245 fallback — common-cathode wiring ("active high", figure 2 in the MA860H manual): all negative inputs go
to the logic GND.

| MA860H terminal | X driver | Y driver | Z driver |
|---|---|---|---|
| PUL+ | 245 B1 (PA0) | 245 B3 (PA1) | 245 B5 (PA2) |
| PUL− | logic GND | logic GND | logic GND |
| DIR+ | 245 B2 (PA4) | 245 B4 (PA5) | 245 B6 (PA6) |
| DIR− | logic GND | logic GND | logic GND |
| ENA+ | 245 B7 (PA15) | 245 B7 | 245 B7 |
| ENA− | logic GND | logic GND | logic GND |

- "Logic GND" is the Blue Pill / buffer (or RichAuto board) GND. It never touches the 24–80 V supply side: the driver's
  optocouplers keep the two apart. Do not join the driver's power GND (−V) to logic GND.
- ENA on the MA860H means "offline": a HIGH (opto lit) **frees the motor**. Grbl drives PA15 HIGH to
  disable the steppers when `$4=0`, so the polarity matches. With `$1=255` Grbl keeps PA15 LOW,
  so the motors always hold. You can also leave ENA+/ENA− unconnected (drivers always enabled) and drop B7.
- One buffer output drives one driver's PUL or DIR (about 10–15 mA each). ENA can share B7 across three drivers
  because it is static. **If the gantry has two Y motors,** feed the second Y driver from its own buffer
  channels (a second 74ACT245 with its inputs tied to PA1/PA5), rather than paralleling two opto inputs on one output.
- DIP switches: SW5–SW8 = **1600 pulses/rev** (SW5 on, SW6 off, SW7 on, SW8 on), which `$100–$102` assume.
  SW1–SW3 = motor current (RMS ≤ the motor's rated phase current). SW4 off = idle current reduction
  (70 % after 200 ms).

## 5. Spindle: MK300 VFD

### On/off (M3 / M5): PB1 → RichAuto Y1 → PC817 → X1

The ULN2803 on the RichAuto board sinks the PC817 LED current from the board's own 5 V:

```
RichAuto 5 V ──[330 Ω]──► PC817 pin 1 (anode)
RichAuto Y1 terminal ───► PC817 pin 2 (cathode)      (Y1 = PB1 via connector pin 29, ULN2803)
PC817 pin 4 (collector) ─► MK300 X1
PC817 pin 3 (emitter)  ──► MK300 COM
```

With the 74ACT245 fallback, it is the circuit of `spindle_setup.md` from the buffer instead:
`245 B8 (PB1) ──[330 Ω]──► PC817 anode`, cathode to logic GND.

- 330 Ω from 5 V gives about 11 mA LED current. Keep the MK300's factory SEL–+24 V link.
- **Keep logic GND and MK300 COM separate.** The PC817 is the isolation barrier.
- MK300: **P0-02 = 1** (run from terminals), P4-00 = 1 (X1 = FWD), **P8-18 = 1** (ignore a run signal present at power-up).
- Grbl turns PB1 off on M5, soft reset (Ctrl-X), any alarm and at power-up. With the 10 kΩ pull-down on PB1,
  unplugging USB lets Y1 go off, which also opens X1.

### Speed (optional): PA8 PWM → 0–10 V → AI1

Today the speed is set on the MK300 panel. For S-word speed control:

```
PA8 ──► PWM-to-0–10 V module (PWM in, GND) ──► MK300 AI1 / GND
          module powered from the MK300 +10 V/+24 V or its own supply
```

- PA8 outputs **10 kHz** at 3.3 V. Pick a module that accepts 3.3 V PWM at 10 kHz; many cheap ones are specified for 1–3 kHz.
  Calibrate it so 100 % PWM gives 10.0 V.
- MK300: **P0-03 = 2** (main frequency from AI1), P4-13 = 0.00 V, P4-15 = 10.00 V (defaults).
- Grbl: `$30` = the spindle rpm at 10 V (max frequency), `$31` = minimum rpm.
- The module's GND joins MK300 GND, so this link is **not isolated**. Use an isolated module, or accept
  that VFD noise can reach the Blue Pill. Not yet tried on this machine.

## 6. Limit switches (PB12 X, PB13 Y, PB14 Z)

```
PB12 ──┬───────── switch ──── GND        (same for PB13, PB14)
       └─[100 nF]─ GND       optional RC filter: 1 kΩ in series + 100 nF at the pin for long cables
```

- Mechanical switches: wire **normally closed** (NC) to GND so a broken wire reads as triggered. Then set `$5=1`.
  Normally open (NO) switches use `$5=0`.
- Two switches on one axis (min and max) go in series (NC) or in parallel (NO) on the same pin.
- 12–24 V inductive NPN sensors: don't put 24 V on any pin. Isolate each through a PC817
  (sensor output → 2.2 kΩ → opto LED; opto transistor between the pin and GND), or use NPN open-collector
  sensors that only sink the pin to GND (sensor 0 V joined to logic GND).
- **F103 Black Pill:** its LED sits on PB12, the **X** limit. With `$5=0` the LED doesn't trip it; an X switch closing
  to GND will light the LED.
- Then enable: `$21=1` (hard limits), `$22=1` (homing), `$23` (homing direction mask), `$130–$132` (travel), `$20=1` (soft limits, after homing works).

## 7. Buttons, door, probe

| Input | Pin | Wire |
|---|---|---|
| Reset / e-stop signal | PB5 | NO button to GND (PB5 is not 5 V tolerant) |
| Feed hold | PB6 | NO button to GND |
| Cycle start | PB7 | NO button to GND |
| Safety door | PB8 | **ignored by this build** (`ENABLE_SAFETY_DOOR_INPUT_PIN` is off in `config.h`); leave it unconnected |
| Probe | PB9 | touch plate to PB9, crocodile clip on the bit to GND. `$6=0` |

The reset button is a Grbl soft reset, **not an emergency stop**. A real e-stop must cut power to the drivers
and the VFD (or the VFD's run input) in hardware.

## 8. Power and grounding

| Supply | Feeds | Ground |
|---|---|---|
| Tablet USB 5 V (OTG) | Blue Pill (and the 74ACT245 in the fallback) | logic GND |
| 24 V DC | RichAuto board (its step-down makes 5 V for the 26LS32, ULN2803 and the PC817 LED) | board GND = logic GND |
| 24–80 V DC (or 18–80 V AC) | MA860H power (+V/−V or AC) | driver power GND, isolated from logic GND |
| Mains | MK300 | PE to the machine frame and the spindle body |

- The logic side draws < 150 mA, which is fine for the tablet's OTG port. The U320 CH340 issue does not apply (native USB).
- Never connect the Blue Pill's 5V pin to another 5 V supply while USB is plugged in. That includes RichAuto pin 1.
- Joining Blue Pill GND to the RichAuto GND puts the board's 24 V supply GND on the logic side. Keep that 24 V supply
  separate from the MA860H motor supply; the drivers' optocouplers still isolate the motor side.
- Earth the VFD, the spindle and the machine frame. Run motor and spindle cables away from the logic wiring.

## 9. Grbl settings for this wiring

```
$0=10        step pulse, µs (MA860H needs ≥ 2.5 µs)
$1=255       keep motors enabled when idle (stops the un-reduced Z from dropping; ENA is not wired)
$2=0         step pulses active high; set 7 if the RichAuto board drives the optos active-LOW (step 3 of the checklist)
$3=0         flip bits per axis after the direction test (X=1, Y=2, Z=4)
$4=0         PA15 HIGH = disabled, matching the MA860H ENA
$5=0         NO limit switches; 1 for NC
$6=0         probe: plate to GND
$30=20000    max spindle rpm
$31=0
$32=0        laser mode off
$100=81.49  $101=81.49  $102=16.30      steps/mm (see grbl-settings: 500 mm jog still to confirm)
$110=1000   $111=1000   $112=1000       mm/min
$120=30     $121=30     $122=10         mm/s²
```

The factory defaults of this port (`$3=31`, `$4=1`, `$5=1`, 5120 steps/mm, 8000 mm/s²) are wrong for this machine.
Check `$$` before the first move.

## 10. First power-up checklist

1. Blue Pill on USB only (no driver power, RichAuto board unplugged). `$$` answers, `?` shows `Idle` with no `Pn:` field.
2. Short each limit pin to GND: `?` shows `Pn:X`, `Pn:Y`, `Pn:Z`. Probe to GND: `Pn:P`.
3. Plug in the RichAuto board, 24 V on, driver power off. Meter: the divider reads about 1.6 V on pins 49/48/47/46/45/44.
   `M3` → Y1 terminal near 0 V (on), `M5` → Y1 off. On a 26LS32 output (X pulse at the driver connector), note the
   idle level and how the driver's PUL+/PUL− are wired to it: idle level that lights the opto → step pulses are
   inverted, set `$2=7`.
4. Driver power on, VFD off. Jog each axis 10 mm at F300. Wrong direction → flip that bit in `$3`.
5. Jog X by 500 mm and measure, to settle 80.0 vs 81.49 steps/mm.
6. VFD on, router bit out: `M3 S20000` starts it, `M5` stops it, Ctrl-X and unplugging USB stop it.
