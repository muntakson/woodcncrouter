# FluidNC for the Macrobase 6-axis ESP32 card

Board: Macrobase-tech "6 axis CNC card" (github.com/Macrobase-tech/CNC-Software, "6 Axis Upgrade"):
ESP32-WROOM (4 MB, DIO flash), CH340K USB serial (1A86:7522), step/dir/enable through I2S shift
registers (FluidNC board "6 Pack"). Shipped with FluidNC v3.0.x (SPIFFS).

## Build (v4.1.1-wcr1)

    git clone --branch v4.1.1 https://github.com/bdring/FluidNC.git fluidnc-src   # ignored by git here
    cd fluidnc-src && git am ../firmware/fluidnc/fluidnc-v4.1.1-wcr1.patch && git tag v4.1.1-wcr1
    export PLATFORMIO_CORE_DIR=$HOME/.platformio-fluidnc      # keeps espressif32 6.x apart from other projects
    pio run -e wifi && pio run -e wifi -t buildfs

The patch pins `platform = espressif32 @ 6.12.0` (GCC 8.4, Arduino 2.0.17): the git-master platform
brings GCC 14 and FluidNC/stdfs fails to compile. The tag makes the banner read `FluidNC v4.1.1-wcr1`,
which the app uses to detect M150.

## Package

`fluidnc-v4.1.1-wcr1-macrobase6axis.zip` holds `0x<offset>.bin` parts:
0x1000 bootloader.bin, 0x8000 partitions.bin (min_littlefs.csv), 0xe000 boot_app0.bin
(from framework-arduinoespressif32/tools/partitions), 0x10000 firmware.bin, 0x3d0000 littlefs.bin
(FluidNC/data: config.yaml = ./config.yaml, index.html.gz). NVS (0x9000) is left alone.

Publish: `backoffice/publish-firmware.sh <zip> "<name>" "<version>" "<notes>" fluidnc`.
The app (Settings → Firmware → (C) FluidNC) flashes it through the ESP32 ROM bootloader
(EspFlasher.java: DTR/RTS auto-reset, 460800 baud, compressed writes, MD5 verify).

## M150 (FluidNC/src/Diag.cpp)

    M150 [X|Y|Z|A] P<s>   1 kHz, 100 us step pulses for s seconds (default X, 10 s, max 60); P0 stops
    M150 [X|Y|Z|A] D1|D0  DIR output high / low (electrical level)

Idle only; handled before the gcode parser; stops on reset and when motion starts.
