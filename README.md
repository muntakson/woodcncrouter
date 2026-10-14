# Wood CNC router

Grbl on one of two controller boards, driven from an Android tablet over USB OTG:
an **Arduino Pro Mini** (ATmega328P) or a **Blue Pill** (STM32F103C8T6). An STM32F103 **Black Pill** runs the
same Blue Pill firmware. All of them run the same machine
(MA860H stepper drivers, MK300 VFD spindle) and are flashed from the app's firmware menu.

| Folder | What |
|---|---|
| `GrblSender/` | Android G-code sender: built-in CH340 driver (Pro Mini) and USB CDC (Blue Pill), drip-feed streaming, G-code editor / large-file viewer, toolpath preview with live tracing, Grbl-planner time estimate, foreground service for long runs, firmware flashing for both boards (STK500v1 and Maple DFU) |
| `backoffice/` | Node server (PM2 `grblsender-backoffice`, port 3077, public at **https://cnc.iotok.org**): log ingest from the app, public landing page at `/` and a console at `/app` (open on the LAN, HTTP Basic auth through the public URL) with Overview, NC files (upload, toolpath preview, load/run on the tablet, delete), Firmware files, APK files, Remote control, Camera, Job log, Logs (live debug console), Devices and HTTP API pages, APK and firmware releases, G-code downloads. Copy `.env.example` to `.env`. `publish-apk.sh` builds and publishes the app, and `publish-firmware.sh` publishes a firmware image <br>**Remote control** at `/app#remote` (`/cnc` redirects there) and `/api/cnc/*` (HTTP API: dashboard login or `Authorization: Bearer CNC_API_KEY`): live status and console, run / hold / resume / stop / load / send, job log (`/api/jobs`), G-code upload. The app keeps a long-poll sync (`POST /api/device/sync`) open; Hold and Stop always work, the rest needs Settings → General → *Allow remote control* on the tablet <br>**Camera** at `/app#camera`: the tablet's camera, live, with the machine state overlaid. It runs only while someone watches: viewers make the next sync reply say `camera.want`, the app (`CameraStreamer`, Settings → Camera) then POSTs JPEG frames to `/api/device/frame`, and the server relays them as MJPEG (`/api/cnc/camera.mjpeg`, also for VLC/OBS), per-frame long-poll or a snapshot. Click the picture to focus (and meter exposure) on the router bit (`POST /api/cnc/camera/focus`; continuous or focus-once-and-hold). Full API reference: `/app/api` |
| `cam/` | DXF → G-code for the plywood geodesic-dome parts (`dome_gcode.py`) and an independent verifier (`verify_gcode.py`); output in `cam/out/` |
| `firmware/` | Pro Mini Grbl patch and hex; `firmware/bluepill/` holds the Blue Pill builds, patches, bootloader and `BUILD.md` |

## Firmware

| | **Arduino Pro Mini** | **Blue Pill / F103 Black Pill (STM32F103)** |
|---|---|---|
| MCU | ATmega328P, 3.3 V / 8 MHz, 32 KB | Cortex-M3, 72 MHz, **64 KB** flash on this board |
| Firmware | Grbl 1.1h ([gnea/grbl](https://github.com/gnea/grbl)) | Grbl 1.1f ([robomechs/6-AXIS-USBCNC-GRBL](https://github.com/robomechs/6-AXIS-USBCNC-GRBL) port) |
| Image | `firmware/grbl-1.1h-promini-8mhz-38400.hex` | `firmware/bluepill/grbl-1.1f-bluepill64-maple.bin` (35,568 B, r3, at 0x08002000) |
| Patch | `firmware/grbl-1.1h-promini-8mhz-38400.patch` | `firmware/bluepill/grbl-1.1f-stm32-classic-maple64.patch` |
| USB link | CH340 serial, **38400 baud** (115200 has 3.5 % error at 8 MHz) | Native USB CDC `0483:5740` (baud ignored) |
| Bootloader | Arduino (STK500v1 @ 57600) | STM32duino/Maple DFU `1EAF:0003`, 8 KB at 0x08000000 |
| Settings stored in | EEPROM | Flash page 0x0800FC00 |
| Spindle on/off (MK300 X1 via PC817) | **D13** (`USE_SPINDLE_DIR_AS_ENABLE_PIN`) | **PB13** (`USE_SPINDLE_ENABLE_PIN`) |
| Spindle PWM | D11 | PA8 |
| Step X / Y / Z | D2 / D3 / D4 | PA0 / PA1 / PA2 |
| Direction X / Y / Z | D5 / D6 / D7 | PA3 / PA4 / PA5 |
| Stepper enable | D8 | PA6 |
| Limits X / Y / Z | D9 / D10 / D12 | PB10 / PB11 / PB12 |
| Probe | A5 | PA15 |
| Reset / hold / start | A0 / A1 / A2 | PB5 / PB6 / PB7 |

Both boards have 3.3 V outputs. Wire the spindle input through an optocoupler; see `spindle_setup.md`.

### Arduino Pro Mini

Build: apply the patch to gnea/grbl 1.1h (F_CPU 8 MHz, 38400 baud, D13 spindle enable) and compile
for `arduino:avr:pro:cpu=8MHzatmega328`. Flash from the app (☰ → Settings → Arduino Pro Mini, then ☰ → Firmware update), or with avrdude:

```sh
avrdude -p m328p -c arduino -P /dev/ttyUSB0 -b 57600 -U flash:w:firmware/grbl-1.1h-promini-8mhz-38400.hex:i
```

The bootloader can't tell 8 MHz from 16 MHz, so flash only 8 MHz builds.

### Blue Pill (STM32F103C8T6) and F103 Black Pill

Most C8T6 chips, this one included, have only 64 KB of flash. grblHAL needs 128 KB, so the 64 KB board
runs the Grbl 1.1f port.

The **Black Pill** used here is also an STM32F103 with 64 KB (OpenOCD: device id `0x20036410`), not the F401/F411
Black Pill, so it has no USB DFU in ROM and takes the same steps and the same Grbl image. Only the bootloader differs
(`generic_boot20_pb12.bin`, LED on PB12). PB12 is also the Z limit input. With `$5=0` the LED doesn't trip it
(no `Pn:Z`), and a Z limit switch to GND will light the LED. Set up 2026-10-03: bootloader via J-Link, Grbl via DFU,
router settings copied. The grblHAL build (`grblhal-20250514-bluepill-maple-usb.bin`) is kept for
128 KB chips and is hidden in the app. Full build steps are in `firmware/bluepill/BUILD.md`.

1. **Bootloader (one-time, over SWD with a J-Link or ST-Link):** BOOT0 = 1, press RESET, then
   ```sh
   openocd -f interface/jlink.cfg -c "transport select swd" -f target/stm32f1x.cfg \
     -c init -c "reset halt" -c "program firmware/bluepill/bootloader/generic_boot20_pc13.bin 0x08000000 verify" \
     -c "reset run" -c shutdown          # ST-Link: interface/stlink.cfg, transport hla_swd
   ```
   Then BOOT0 = 0. See `firmware/bluepill/bootloader/README.md`.
   For the F103 **Black Pill**, write `generic_boot20_pb12.bin` instead (its LED is on PB12, not PC13).
   Add `-c "set CPUTAPID 0"` before the target file if OpenOCD warns about an unexpected idcode.
2. **Grbl over USB DFU:** from the app (☰ → Settings → Blue Pill, then ☰ → Firmware update), or replug the board and, within
   about 1.5 s while it shows `1EAF:0003`:
   ```sh
   dfu-util -d 1eaf:0003 -a 2 -D firmware/bluepill/grbl-1.1f-bluepill64-maple.bin -R
   ```
3. **Check:** `$$` must answer with the settings and `ok`. If only `?` answers, the build is missing
   the `volatile` serial-buffer fix in the patch. If the board stays `1EAF:0003` after a plain plug-in
   (Grbl only appears after `dfu-util -R`), the build is missing the r3 D+ re-enumeration pulse.

USB error `-71` / "unable to enumerate" after a replug means BOOT0 is still 1.
Once the bootloader is running with BOOT0 = 0, the J-Link no longer connects ("cannot read IDR"); set BOOT0 = 1 for SWD.
The port's default `$` settings don't suit this machine (`$3=31`, `$4=1`, `$5=1`, 5120 steps/mm), so copy the
values below before the first move.

## CAM

```sh
cd cam && python3 -m venv .venv && .venv/bin/pip install ezdxf shapely matplotlib
.venv/bin/python dome_gcode.py "../plywood hex dome CNC layout (1).dxf" -o out/dome.nc --feed 2000 --plunge 600 --step 10
.venv/bin/python verify_gcode.py "../plywood hex dome CNC layout (1).dxf" out/dome.nc 8
```

Origin: X0 Y0 = lower-left corner of the sheet stack, Z0 = top of the stack.

## Machine (X/Y: NEMA34, 18T→90T belt, rack & 24T pinion; Z: direct → 5 mm-lead screw; drivers 1600 p/rev)

Target settings (2026-10-14): `$100=$101=84.88` (24T pinion), `$102=320` (motor direct on a 5 mm-lead screw; the 1:5 Z belt assumed earlier does not exist, measured 2026-10-14), `$112=1000`. The boards were set on 2026-10-03 to 81.49 / 16.30 for an assumed 25T pinion on all axes; rewrite them. Other settings: `$110=$111=1000`,
`$120=$121=30`, `$122=10`, `$3=$4=$5=0`. Confirm the steps/mm with measured jogs. Recommended: `$1=255`, so the
Z axis holds its position when idle.
