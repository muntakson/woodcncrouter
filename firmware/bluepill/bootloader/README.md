# STM32duino bootloader 2.0 for the Blue Pill

`generic_boot20_pc13.bin` from [rogerclarkmelbourne/STM32duino-bootloader](https://github.com/rogerclarkmelbourne/STM32duino-bootloader/tree/master/binaries)
(SHA-256 `a25ee15f986d7102857cc682478bde45e52333431b16a2abbffa339eeca4ccec`, 22,268 bytes).
It contains the bootloader (0x08000000–0x08001FFF, LED on PC13) followed by an upstream demo sketch at 0x08002000,
which prints "Congratulations, you have installed the STM32duino bootloader" on its USB serial port (`1EAF:0004`).
Grbl replaces the demo when it is flashed over DFU.

## Installing with a J-Link and OpenOCD (as done on 2026-10-02)

```sh
# BOOT0 = 1, RESET (ROM code keeps SWD enabled), then:
openocd -f interface/jlink.cfg -c "transport select swd" -c "adapter speed 1000" -f target/stm32f1x.cfg \
  -c init -c halt -c "stm32f1x options_read 0" -c shutdown            # check "read protection"
# only if read protection is on – this MASS-ERASES the whole chip:
openocd ... -c init -c halt -c "stm32f1x unlock 0" -c "reset halt" -c shutdown
openocd ... -c init -c "reset halt" -c "program generic_boot20_pc13.bin 0x08000000 verify" -c "reset run" -c shutdown
# BOOT0 = 0
```

Always end with `reset run`: without it, the CPU stays halted in OpenOCD's flash routine,
and neither the RESET button nor re-plugging USB starts it while the debugger is attached.
After a reset, the bootloader shows as `1EAF:0003` (DFU) for about 1.5 s, then the firmware runs.
