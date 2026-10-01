# Wood CNC router

Grbl 1.1h on an Arduino Pro Mini (3.3 V / 8 MHz, CH340, 38400 baud), driven from an
Android tablet over USB OTG.

| Folder | What |
|---|---|
| `GrblSender/` | Android G-code sender: built-in CH340 driver, drip-feed streaming, G-code editor / large-file viewer, toolpath preview with live tracing, Grbl-planner time estimate, foreground service for long runs |
| `backoffice/` | Node server (PM2 `grblsender-backoffice`, port 3077): log ingest from the app, LAN dashboard with live debug console, APK releases, G-code downloads. Copy `.env.example` to `.env`. `publish-apk.sh` builds and publishes the app |
| `cam/` | DXF → G-code for the plywood geodesic-dome parts (`dome_gcode.py`) and an independent verifier (`verify_gcode.py`); output in `cam/out/` |
| `firmware/` | Patch against [gnea/grbl](https://github.com/gnea/grbl) for the 8 MHz Pro Mini (F_CPU 8 MHz, 38400 baud) and the built hex |

## CAM

```sh
cd cam && python3 -m venv .venv && .venv/bin/pip install ezdxf shapely matplotlib
.venv/bin/python dome_gcode.py "../plywood hex dome CNC layout (1).dxf" -o out/dome.nc --feed 2000 --plunge 600 --step 10
.venv/bin/python verify_gcode.py "../plywood hex dome CNC layout (1).dxf" out/dome.nc 8
```

Origin: X0 Y0 = lower-left corner of the sheet stack, Z0 = top of the stack.

## Machine (X/Y: NEMA34, 18T→90T belt, rack & ~25T pinion; Z: rack & pinion direct; drivers 1600 p/rev)

Starting values: `$100=$101=81.49`, `$102=16.30`, `$1=255` — confirm with measured jogs.
