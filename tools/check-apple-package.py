"""Fail packaging before release if required Apple permissions or legacy encodings disappear."""
import argparse
import pathlib
import plistlib
import subprocess
import zipfile


def check_runtime(root):
    # jpackage strips command-line launchers such as bin/java. Inspect the actual linked image with the build JDK.
    listing = subprocess.check_output(["jimage", "list", str(root / "lib/modules")], text=True)
    assert "Module: jdk.charsets" in listing, "Packaged Java is missing jdk.charsets"
    classes = {line.strip().rsplit("/", 1)[-1] for line in listing.splitlines()}
    for name in ("SJIS", "GBK", "Big5", "EUC_KR", "MS1251"):
        assert name + ".class" in classes, "Missing charset implementation: " + name


def check_macos(root):
    contents = root / "Contents"
    info = plistlib.loads((contents / "Info.plist").read_bytes())
    assert info.get("NSMicrophoneUsageDescription"), "Missing microphone usage description"
    assert info.get("NSLocalNetworkUsageDescription"), "Missing local network usage description"
    check_runtime(contents / "runtime/Contents/Home")
    subprocess.run(["codesign", "--verify", "--deep", "--strict", str(root)], check=True)


def check_ios(ipa):
    with zipfile.ZipFile(ipa) as archive:
        info = plistlib.loads(archive.read("Payload/mLabeler.app/Info.plist"))
        assert info.get("NSMicrophoneUsageDescription"), "Missing microphone usage description"
        assert info.get("NSLocalNetworkUsageDescription"), "Missing local network usage description"
        assert info.get("NSAppTransportSecurity", {}).get("NSAllowsLocalNetworking"), "Local toolkit HTTP is blocked"
        assert info.get("UIFileSharingEnabled"), "Documents are inaccessible from Files"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("macos", "ios", "runtime"))
    parser.add_argument("path", type=pathlib.Path)
    args = parser.parse_args()
    {"macos": check_macos, "ios": check_ios, "runtime": check_runtime}[args.kind](args.path.resolve())
    print("Apple package checks passed")
