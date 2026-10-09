# RichAuto breakout board: 50-pin SCSI controller connector

Pinout of the RichAuto I/O breakout board (`RichAuto_IOboard_1B-A1X-10.png`), measured on the board.
How the Blue Pill is wired to it: `bluepill_wiring.md`, section 3.1.

## Board

- Power: **24 V** input. An on-board step-down converter makes 5 V.
- Step / dir: differential pairs into **26LS32** line receivers. The 26LS32 outputs go to the driver connectors.
- Outputs Y1–Y8: driven by a **ULN2803** (open-collector, sinks to board GND).
- Inputs X1–X8: serialized by an **SN65HVS885**, sent to the SOP pin. An SN65LVDS049 (via a 74LCH245) turns SOP, CLK and LD into
  LVDS differential signals.

## Step and direction (differential, into 26LS32)

| Signal | + pin | − pin |
|---|---|---|
| X pulse | 3 | 4 |
| X dir | 5 | 6 |
| Y pulse | 7 | 8 |
| Y dir | 9 | 10 |
| Z pulse | 11 | 12 |
| Z dir | 13 | 14 |
| C pulse | 15 | 16 |
| C dir | 17 | 18 |

## Inputs X1–X8 (HVS885 serial, differential)

| Signal | + pin | − pin |
|---|---|---|
| CLK | 26 | 25 |
| LD | 27 | 28 |
| SOP | 36 | 35 |

Signal path on the board. CLK and LD come in from the controller, and SOP (the serial input data) goes out
to it:

```
+----------------------------------------------------------------------------------------+
|                                    YOUR INPUT BOARD                                    |
|                                                                                        |
|  +------------+        +---------------+        +---------------+                      |
|  |  SN65HVS885|        |    74LCH245   |        |  SN65LVDS049  |                      |
|  |            |        |               |        |               |      CNC CABLE       |
|  |         CLK|<-------|Pin 17   Pin 3 |<-------|Pin 15  Pins 1&2|<================(CLK)
|  |            |        |               |        |        [750Ω] |                      |
|  |          LD|<-------|Pin 16   Pin 4 |<-------|Pin 14  Pins 3&4|<=================(LD)
|  |            |        |               |        |        [750Ω] |                      |
|  |         SOP|------->|Pin 2   Pin 18 |------->|Pin 10  Pins 7&8|================>(SOP)
|  +------------+        +---------------+        +---------------+                      |
+----------------------------------------------------------------------------------------+
```

### Signal architecture

1. **Clock (CLK line):** sent by the CNC controller as a differential signal. It arrives at 049 pins 1 & 2, is
   cleaned up across the 750 Ω biasing bridge, steps down through the 245 buffer (pins 3 → 17), and drives the
   clock cycles of the HVS885.
2. **Latch / load (LD line):** sent by the CNC controller. It arrives at 049 pins 3 & 4, passes across the 750 Ω
   bridge, travels through the 245 buffer (pins 4 → 16), and tells the HVS885 to freeze the state of the 8
   digital inputs.
3. **Serial output port (SOP line):** driven by the board. The 8 bits of input data leave the HVS885 shift
   register, pass through the 245 buffer (pins 2 → 18) to 049 pin 10, and leave through 049 pins 7 & 8 back to
   the CNC controller.

| Chip | Role |
|---|---|
| SN65LVDS049 | LVDS transceiver: receives CLK and LD from the cable, sends SOP back |
| 74LCH245 | buffer between the LVDS chip and the HVS885 |
| SN65HVS885 | 8-channel 24 V digital-input serializer (X1–X8) |

The inputs are LVDS, not RS-422 like the 26LS32 step/dir inputs. Reading X1–X8 would need firmware that clocks
the HVS885 (SPI-like) and an LVDS link, which stock Grbl doesn't have. So limit switches, probe and buttons go
straight to the Blue Pill (`bluepill_wiring.md`, sections 6 and 7).

## Outputs Y1–Y8 (ULN2803 inputs)

| Output | Pin |
|---|---|
| Y1 | 44 |
| Y2 | 43 |
| Y3 | 46 |
| Y4 | 45 |
| Y5 | 47 |
| Y6 | 49 |
| Y7 | 37 |
| Y8 | 38 |

## Power and ground

| Pin | Signal |
|---|---|
| 1 | 5 V out (from the board's step-down, to supply an external board) |
| 2, 30, 34, 40, 42, 48, 50 | GND |

## Connector diagram

50-pin SCSI connector (two rows, 0.05" pitch): odd pins in one row, even pins in the other, so each pair
n / n + 1 sits side by side. Every differential pair uses one such odd/even pair. "—" = not identified.

```
          -------
   5V out | 1| 2| GND
  Xpulse+ | 3| 4| Xpulse-
    Xdir+ | 5| 6| Xdir-
  Ypulse+ | 7| 8| Ypulse-
    Ydir+ | 9|10| Ydir-
  Zpulse+ |11|12| Zpulse-
    Zdir+ |13|14| Zdir-
  Cpulse+ |15|16| Cpulse-
    Cdir+ |17|18| Cdir-
        — |19|20| —
        — |21|22| —
        — |23|24| —
     CLK- |25|26| CLK+
      LD+ |27|28| LD-
        — |29|30| GND
        — |31|32| —
        — |33|34| GND
     SOP- |35|36| SOP+
       Y7 |37|38| Y8
        — |39|40| GND
        — |41|42| GND
       Y2 |43|44| Y1
       Y4 |45|46| Y3
       Y5 |47|48| GND
       Y6 |49|50| GND
          -------
```

Pins 19–24, 29, 31–33, 39, 41 are not identified (unknown or unconnected).
