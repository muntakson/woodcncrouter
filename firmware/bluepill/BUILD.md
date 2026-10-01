# Blue Pill (STM32F103C8T6) firmware with the STM32duino bootloader

Both builds load at **0x08002000** (above the 8 KB STM32duino/Maple bootloader), are flashed over
USB DFU (`1EAF:0003`, alt "0x8002000") by Grbl Sender (☰ → (B) Blue Pill firmware…) or
`dfu-util -d 1eaf:0003 -a 2 -D <bin>`, and talk to the tablet over native USB CDC (`0483:5740`).

| Build | Chip | Size | In the app |
|---|---|---|---|
| **`grbl-1.1f-bluepill64-maple.bin`** | **64 KB C8T6 (this machine)** | 35,492 B of 55 KB | listed (target `bluepill`) |
| `grblhal-20250514-bluepill-maple-usb.bin` | 128 KB only | 120,584 B of 120,832 B | hidden (target `bluepill-128k`) |

## 1. Grbl 1.1f for 64 KB chips (in use)

Source: [robomechs/6-AXIS-USBCNC-GRBL](https://github.com/robomechs/6-AXIS-USBCNC-GRBL) (Grbl 1.1f STM32F103C8 port,
StdPeriph + ST USB-FS CDC). `grbl-1.1f-stm32-classic-maple64.patch` adds `Makefile.maple` and
`stm32_flash_maple64.ld`, and makes `VECT_TAB_OFFSET` / `EEPROM_START_ADDRESS` overridable.

```sh
git clone https://github.com/robomechs/6-AXIS-USBCNC-GRBL.git grbl-stm32-classic
cd grbl-stm32-classic && git apply ../firmware/bluepill/grbl-1.1f-stm32-classic-maple64.patch
make -f Makefile.maple        # system arm-none-eabi-gcc 13, -Os -flto
```

Flash layout: 0x08000000 bootloader (8 KB) · 0x08002000 firmware (55 KB max) · **0x0800FC00 settings
(1 KB page)**. The upstream default 0x0801FC00 would be outside a 64 KB chip.

### Pin map (this port, not the grblHAL one)

| Function | Pin |
|---|---|
| X / Y / Z step | PA0 / PA1 / PA2 |
| X / Y / Z direction | PA3 / PA4 / PA5 |
| Stepper disable | PA6 |
| Limits X / Y / Z | PB10 / PB11 / PB12 |
| **Spindle enable (MK300 X1 via PC817)** | **PB13** (`USE_SPINDLE_ENABLE_PIN`: on with M3, off with M5/reset/alarm) |
| Spindle direction / PWM | PB14 / PA8 |
| Coolant flood / mist | PB3 / PB4 |
| Reset / feed hold / cycle start / door | PB5 / PB6 / PB7 / PB8 |
| Probe | PA15 |

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
