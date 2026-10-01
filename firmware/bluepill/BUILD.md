# grblHAL for the Blue Pill (STM32F103C8T6, 128 KB) with the STM32duino bootloader

`grblhal-20250514-bluepill-maple-usb.bin`: raw binary for **0x08002000**, flashed over
USB DFU (Maple bootloader 2.0, `1EAF:0003`, DFU alt "0x8002000") by Grbl Sender
(☰ → Blue Pill firmware…) or `dfu-util -d 1eaf:0003 -a 2 -D <bin>`.

## Source and build

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

## Pin map (grblHAL `BOARD_CNC3040`)

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
