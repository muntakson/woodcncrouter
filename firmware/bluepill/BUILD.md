# Blue Pill (STM32F103C8T6) firmware with the STM32duino bootloader

Both builds load at **0x08002000** (above the 8 KB STM32duino/Maple bootloader), are flashed over
USB DFU (`1EAF:0003`, alt "0x8002000") by Grbl Sender (☰ → Settings → Blue Pill, then ☰ → Firmware update) or
`dfu-util -d 1eaf:0003 -a 2 -D <bin>`, and talk to the tablet over native USB CDC (`0483:5740`).

| Build | Chip | Size | In the app |
|---|---|---|---|
| **`grbl-1.1f-bluepill64-maple.bin`** | **64 KB C8T6 (this machine)** | 36,100 B of 55 KB (r6) | listed (target `bluepill`) |
| `grblhal-20250514-bluepill-maple-usb.bin` | 128 KB only | 120,584 B of 120,832 B | hidden (target `bluepill-128k`) |

## 1. Grbl 1.1f for 64 KB chips (in use)

Source: [robomechs/6-AXIS-USBCNC-GRBL](https://github.com/robomechs/6-AXIS-USBCNC-GRBL) (Grbl 1.1f STM32F103C8 port,
StdPeriph + ST USB-FS CDC). `grbl-1.1f-stm32-classic-maple64.patch` adds `Makefile.maple` and
`stm32_flash_maple64.ld`, and makes `VECT_TAB_OFFSET` / `EEPROM_START_ADDRESS` overridable.
**r3 (2026-10-03):** `USB_Reenumerate()` (`usb/hw_config.c`, called from `main()` before `USB_Init()`) holds PA12/D+
low for about 50 ms. The board has no D+ switch and the port's `USB_Cable_Config()` is empty, so when the bootloader
jumped to Grbl the host never noticed. `1EAF:0003` stayed listed (stale), and Grbl only came up after a host bus reset
(`dfu-util -R`). On the Android tablet, which can't send one, Grbl never appeared. With the pulse, Grbl enumerates as
`0483:5740` about 3 s after any plug-in or RESET.

It also marks `serial_rx_buffer_head` / `serial_tx_buffer_head` `volatile`: with `-flto` the main loop
otherwise never sees bytes the USB interrupt adds, so Grbl answers `?` but ignores every line (`$$`, G-code).

```sh
git clone https://github.com/robomechs/6-AXIS-USBCNC-GRBL.git grbl-stm32-classic
cd grbl-stm32-classic && git apply ../firmware/bluepill/grbl-1.1f-stm32-classic-maple64.patch
make -f Makefile.maple        # system arm-none-eabi-gcc 13, -Os -flto
```

Flash layout: 0x08000000 bootloader (8 KB) · 0x08002000 firmware (55 KB max) · **0x0800FC00 settings
(1 KB page)**. The upstream default 0x0801FC00 would be outside a 64 KB chip.

### Pin map (this port, not the grblHAL one)

`inc/config.h` selects `ABC_AXIS_EXAMPLE` with `AA_AXIS` (X, Y, Z + A), so the pins come from that block
of `inc/cpu_map.h`, **not** from `CPU_MAP_STM32F103` (which is commented out).

| Function | Pin |
|---|---|
| X / Y / Z / A step | PA0 / PA1 / PA2 / PA3 |
| X / Y / Z / A direction | PA4 / PA5 / PA6 / PA7 |
| Stepper disable | PA15 |
| Limits X / Y / Z / A | PB12 / PB13 / PB14 / PB15 |
| **Spindle enable (MK300 X1)** | **PB1** (on with M3, off with M5/reset/alarm) |
| Spindle direction / PWM | PB0 / PA8 |
| Coolant flood / mist | PB4 / PB3 |
| Reset / feed hold / cycle start / door | PB5 / PB6 / PB7 / PB8 |
| Probe | PB9 |

### M150: wiring check (r4–r6)

`M150 [X|Y|Z|A] P<s>` puts 1 kHz pulses, each 100 µs high (r5; r4 was a 50 % square wave), on that axis's step pin
(X PA0 / Y PA1 / Z PA2 / A PA3; X when no axis is given; the axis letter is new in r6) for `<s>` seconds (default 10,
`P0` stops it), from TIM4 and without moving the machine (`src/diag.c`). It waits for queued motion,
is refused in alarm or while jogging, and any new motion or a reset ends it. The app's
Settings → Diagnosis → Xpulse / Ypulse / Zpulse buttons use it.

All outputs are 3.3 V, like the Pro Mini. See the upstream `STM32F103-USBCNC-Pinout.png`.

## 2. grblHAL 20250514 (128 KB chips only, kept for reference)

### Source and build

- grblHAL/STM32F1xx tag **20250514**: the last release with 128 KB F103 support
  (later code no longer fits, see the upstream README).
- `grblhal-20250514-bluepill-maple.patch`: our changes (env `bluepill_f103c8_128k_USB_maple`,
  bootloader offset in `STM32F103C8TX_FLASH.ld`, smaller link-time heap reservation).

```sh
git clone --recursive https://github.com/grblHAL/STM32F1xx.git grblhal-stm32f1
cd grblhal-stm32f1 && git checkout 20250514 && git submodule update --init --recursive
git apply ../firmware/bluepill/grblhal-20250514-bluepill-maple.patch
pio run -e bluepill_f103c8_128k_USB_maple      # -> .pio/build/<env>/firmware.bin
```

Fitting 118 KB (128 KB − 8 KB bootloader − 2 KB settings) needed:
GCC 12.3 (`platform_packages`), `-flto`, `COMPATIBILITY_LEVEL=10` (plain Grbl protocol/settings,
no grblHAL extensions, no G59.1–G59.3) and `NGC_PARAMETERS_ENABLE=0` (no named `#<…>` parameters).
Result: 120,584 of 120,832 bytes. The planner allocates its blocks from the heap at start-up and
uses as many as fit (check `$I`).

USB: native USB CDC, `0483:5740` "STM32 Virtual ComPort". Requires a chip with **128 KB** flash
(most C8T6 have it; check with `st-info --probe`).

### Pin map (grblHAL `BOARD_CNC3040`)

| Function | Pin |
|---|---|
| X step / dir | PA0 / PA1 |
| Y step / dir | PA2 / PA3 |
| Z step / dir | PA4 / PA5 |
| Stepper enable | PB9 |
| Limits X / Y / Z | PB10 / PB11 / PB12 |
| Spindle enable (MK300 X1 via PC817) | PB1 |
| Spindle PWM / direction | PA8 / PB0 |
| Coolant flood / mist | PB3 / PB4 |
| Reset / feed hold / cycle start | PB5 / PB14 / PB15 |
| Probe / safety door | PB13 / PB8 |

All outputs are 3.3 V, like the Pro Mini.
