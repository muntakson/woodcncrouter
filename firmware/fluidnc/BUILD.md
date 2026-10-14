# FluidNC for the Macrobase 6-axis ESP32 card

Board: Macrobase-tech "6 axis CNC card" (github.com/Macrobase-tech/CNC-Software, "6 Axis Upgrade"):
ESP32-WROOM (4 MB, DIO flash), CH340K USB serial (1A86:7522), step/dir/enable through I2S shift
registers (FluidNC board "6 Pack"). Shipped with FluidNC v3.0.x (SPIFFS).

## Build (v4.1.1-wcr2)

    git clone --branch v4.1.1 https://github.com/bdring/FluidNC.git fluidnc-src   # ignored by git here
    cd fluidnc-src && git am ../firmware/fluidnc/fluidnc-v4.1.1-wcr2.patch && git tag v4.1.1-wcr2
    export PLATFORMIO_CORE_DIR=$HOME/.platformio-fluidnc      # keeps espressif32 6.x apart from other projects
    pio run -e wifi && pio run -e wifi -t buildfs

The patch pins `platform = espressif32 @ 6.12.0` (GCC 8.4, Arduino 2.0.17): the git-master platform
brings GCC 14 and FluidNC/stdfs fails to compile. The tag makes the banner read `FluidNC v4.1.1-wcr2`,
which the app uses to detect M150 (any `-wcr` tag).

## Package

`fluidnc-v4.1.1-wcr1-macrobase6axis.zip` holds `0x<offset>.bin` parts:
0x1000 bootloader.bin, 0x8000 partitions.bin (min_littlefs.csv), 0xe000 boot_app0.bin
(from framework-arduinoespressif32/tools/partitions), 0x10000 firmware.bin, 0x3d0000 littlefs.bin
(FluidNC/data: config.yaml = ./config.yaml, index.html.gz). NVS (0x9000) is left alone.

`fluidnc-v4.1.1-wcr2-macrobase6axis.zip` has the same parts **without 0x3d0000**: flashing it over wcr1
keeps the board's config.yaml (same partition table). Use the wcr1 zip only for a fresh card.

Publish: `backoffice/publish-firmware.sh <zip> "<name>" "<version>" "<notes>" fluidnc`.
The app (Settings → Firmware → (C) FluidNC) flashes it through the ESP32 ROM bootloader
(EspFlasher.java: DTR/RTS auto-reset, 460800 baud, compressed writes, MD5 verify).

## M150 (FluidNC/src/Diag.cpp)

    M150 [X|Y|Z|A] P<s>   1 kHz, 100 us step pulses for s seconds (default X, 10 s, max 60); P0 stops
    M150 [X|Y|Z|A] D1|D0  DIR output high / low (electrical level)

Idle only; handled before the gcode parser; stops on reset and when motion starts.

## Home sensors after an interrupted homing (wcr2, Machine/Homing.cpp)

FluidNC disarms a home sensor input (limit_all_pin) when it fires in an approach phase and marks
its motor "limited" (no step pulses); only the pull-off phase rearms it. Stock v4.1.1 left both in
place when homing failed or was reset (E-STOP, ALARM:6): Pn: kept showing the sensor triggered,
the next $H gave ALARM:12 "Ambiguous limit switch", and G0/G1 replied ok and advanced MPos while
that motor never stepped (M150 still worked). wcr2's `Homing::fail()` leaves the approach phase,
clears "limited" on every motor and rearms every home sensor input. On wcr1 a board restart clears it.
