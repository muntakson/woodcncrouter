"""Illustrations for the app's Wiring and CNC setup pages (Gemini image model).

Usage: python3 gen_images.py [slug ...]   -> art/out/<slug>.png (originals)
Then  python3 gen_images.py --install     -> resized JPEGs in app/src/main/res/drawable-nodpi/

The pictures are illustrations only; the exact pins and values are text tables in the app.
"""
import os, sys, json, base64, urllib.request, urllib.error, time

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
RES = os.path.join(HERE, "..", "app", "src", "main", "res", "drawable-nodpi")
MODEL = "gemini-3.1-flash-image"

def secret(name):
    for line in open(os.path.expanduser("~/.secrets")):
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip().strip('"\'')
    raise KeyError(name)

BASE = (
    "Style: vivid, clean technical illustration, flat vector with soft shading, dark navy background (#1D2024), "
    "crisp outlines, generous spacing, like a high-quality maker-magazine infographic. Wide 16:9 composition. "
    "TEXT RULE: render ONLY the exact English labels listed in the prompt, spelled exactly as given, in a clean sans-serif font, "
    "each label exactly once. Never invent extra words, captions, pin numbers, values, logos, legends or pseudo-text. "
    "If a label would be hard to render legibly, leave it out."
)
WIRES = (" Wires are bright and colour-coded (red = +, black = GND, yellow = pulse, blue = direction, green = spindle signal, "
         "orange = home switch), routed neatly at right angles without crossing where possible. No colour legend.")
STYLE = {"wiring_promini": BASE + WIRES, "wiring_bluepill": BASE + WIRES,
         "cnc_drivetrain": BASE + " No cables, no wires.", "cnc_router": BASE}

PROMPTS = {
    "wiring_promini": (
        "Wiring overview of a CNC router controller. Left: a small blue Arduino Pro Mini board labelled 'Arduino Pro Mini'. "
        "From it, coloured signal wires run to three identical black stepper-driver boxes on the right labelled 'MA860H X', "
        "'MA860H Y', 'MA860H Z'. "
        "Each driver box has its signal terminals on its LEFT side, facing the controller, labelled 'PUL+', 'PUL-', 'DIR+', 'DIR-', and its motor terminals on its RIGHT side labelled 'A+', 'A-', 'B+', 'B-', wired to its own large square stepper motor: three separate motors in a column on the far right, one per driver, each labelled 'NEMA34'. At the bottom: a small optocoupler chip labelled 'PC817' between the Arduino "
        "and a grey industrial inverter box labelled 'MK300' with terminals 'X1' and 'COM'; the inverter drives a cylindrical "
        "spindle motor labelled 'Spindle'. At the top: three small limit switches labelled 'Home X', 'Home Y', 'Home Z' wired "
        "back to the Arduino. A tablet labelled 'Tablet' connects to the Arduino by a USB cable."
    ),
    "wiring_bluepill": (
        "Wiring overview of a CNC router controller. Left: a small blue STM32 'Blue Pill' development board, "
        "labelled 'Blue Pill STM32F103'. From it, coloured signal wires run to three identical black stepper-driver "
        "boxes on the right labelled 'MA860H X', 'MA860H Y', 'MA860H Z'. "
        "Each driver box has its signal terminals on its LEFT side, facing the controller, labelled 'PUL+', 'PUL-', 'DIR+', 'DIR-', and its motor terminals on its RIGHT side labelled 'A+', 'A-', 'B+', 'B-', wired to its own large square stepper motor: three separate motors in a column on the far right, one per driver, each labelled 'NEMA34'. At the bottom: a small optocoupler chip "
        "labelled 'PC817' between the board and a grey industrial inverter box labelled 'MK300' with terminals 'X1' and 'COM'; "
        "the inverter drives a cylindrical spindle motor labelled 'Spindle'. At the top: three small limit switches labelled "
        "'Home X', 'Home Y', 'Home Z' wired back to the board. A tablet labelled 'Tablet' connects by a USB cable."
    ),
    "cnc_drivetrain": (
        "Cut-away mechanical drawing of a CNC router axis drive train, shown in two rows. Top row labelled 'X / Y axis': "
        "a large square stepper motor labelled 'NEMA34' with a small toothed pulley, a timing belt to a pulley five times larger, "
        "labelled '1:5', on a shaft that carries a small spur gear labelled 'Pinion' meshing with a long straight toothed bar "
        "labelled 'Rack'. Bottom row labelled 'Z axis': the same 'NEMA34' motor driving the 'Pinion' directly on its shaft, "
        "meshing with a vertical 'Rack', no belt. Small arrows show rotation and linear motion. No cables, no wires, no colour legend."
    ),
    "cnc_router": (
        "Three-quarter view of a large gantry CNC wood router: a long flat bed with a plywood sheet, a gantry bridge across the "
        "short side of the bed, a vertical carriage with a powerful cylindrical spindle motor and a collet nut at the tip, "
        "rack-and-pinion drives along both long edges of the bed. Exactly two dimension arrows: one beside the bed's longer edge "
        "with the text '2500 mm', and one beside the bed's shorter edge with the text '1325 mm'. Small axis arrows: 'X' along the "
        "longer edge, 'Y' along the gantry, 'Z' vertical at the spindle. One label next to the spindle reads '4 kW ER32 24000 RPM'. A grey "
        "inverter box beside the machine is labelled 'MK300', with one cable to the spindle. No other labels."
    ),
}

def generate(slug, key, retries=3):
    path = os.path.join(OUT, slug + ".png")
    if os.path.exists(path) and os.path.getsize(path) > 10000:
        print(f"  skip {slug} (exists)"); return True
    body = {"contents": [{"parts": [{"text": PROMPTS[slug] + "\n\n" + STYLE[slug]}]}],
            "generationConfig": {"responseModalities": ["IMAGE"]}}
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent?key={key}"
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
            r = json.load(urllib.request.urlopen(req, timeout=240))
            for p in r["candidates"][0]["content"]["parts"]:
                if "inlineData" in p:
                    data = base64.b64decode(p["inlineData"]["data"])
                    open(path, "wb").write(data)
                    print(f"  OK   {slug} ({len(data) // 1024} KB)"); return True
            print(f"  WARN {slug}: no image part")
        except urllib.error.HTTPError as e:
            print(f"  HTTP {e.code} {slug}: {e.read().decode()[:200]}")
        except Exception as e:
            print(f"  ERR  {slug}: {e}")
        time.sleep(4 * (attempt + 1))
    return False

def install():
    from PIL import Image
    os.makedirs(RES, exist_ok=True)
    for slug in PROMPTS:
        src = os.path.join(OUT, slug + ".png")
        if not os.path.exists(src): print(f"  missing {slug}"); continue
        im = Image.open(src).convert("RGB")
        im.thumbnail((1280, 1280))
        dst = os.path.join(RES, "art_" + slug + ".jpg")
        im.save(dst, "JPEG", quality=82, optimize=True)
        print(f"  {dst} {im.size} {os.path.getsize(dst) // 1024} KB")

if __name__ == "__main__":
    if sys.argv[1:] == ["--install"]:
        install(); sys.exit()
    os.makedirs(OUT, exist_ok=True)
    key = secret("GEMINI_API_KEY")
    for slug in (sys.argv[1:] or PROMPTS):
        generate(slug, key)
