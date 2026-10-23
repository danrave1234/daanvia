"""Capture only the selected Android emulator; never inspects the Windows desktop."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8")
parser = argparse.ArgumentParser()
parser.add_argument("name")
parser.add_argument("--serial", default="emulator-5554")
parser.add_argument("--screen-only", action="store_true")
args = parser.parse_args()
if not args.serial.startswith("emulator-"):
    parser.error("This verification tool is restricted to Android emulators")
if not args.name.replace("-", "").replace("_", "").isalnum():
    parser.error("Use a simple screenshot name")
sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "AppData/Local/Android/Sdk")))
adb = [str(sdk / "platform-tools/adb.exe"), "-s", args.serial]
subprocess.run(adb + ["shell", "rm", "-f", "/sdcard/daanvia-ui.xml"], check=True)
xml = None
if not args.screen_only:
    try:
        result = subprocess.run(adb + ["shell", "uiautomator", "dump", "/sdcard/daanvia-ui.xml"], capture_output=True, timeout=25)
        if b"dumped" in result.stdout:
            xml = subprocess.check_output(adb + ["shell", "cat", "/sdcard/daanvia-ui.xml"])
    except subprocess.TimeoutExpired:
        pass
directory = Path(__file__).resolve().parents[1] / "dist/verification"
directory.mkdir(parents=True, exist_ok=True)
if xml:
    (directory / f"{args.name}.xml").write_bytes(xml)
image = directory / f"{args.name}.png"
image.write_bytes(subprocess.check_output(adb + ["exec-out", "screencap", "-p"]))
print(image)
if xml is None:
    print("Live screen did not become idle; screenshot captured, no fresh UI hierarchy.")
    sys.exit(0)
for node in ET.fromstring(xml).iter("node"):
    if node.get("text") or node.get("content-desc"):
        print(node.get("text"), node.get("content-desc"), node.get("bounds"))
