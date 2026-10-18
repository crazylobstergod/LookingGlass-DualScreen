"""Import only arm64 libraries from the bridge-enabled native engine build."""
from pathlib import Path
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
dest = root / "app/src/main/jniLibs/arm64-v8a"
with zipfile.ZipFile(sys.argv[1]) as archive:
    names = ["lib/arm64-v8a/liblooking_glass.so", "lib/arm64-v8a/libc++_shared.so"]
    contents = {Path(name).name: archive.read(name) for name in names}
    if b"LOOKINGGLASS_EXPORT" not in contents["liblooking_glass.so"]:
        raise SystemExit("Engine lacks the dual-screen bridge; apply the supplied native patch first.")
    dest.mkdir(parents=True, exist_ok=True)
    for name, data in contents.items():
        (dest / name).write_bytes(data)
        print("Imported", name)
