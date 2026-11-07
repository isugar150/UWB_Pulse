#!/usr/bin/env python3
"""Package a previously validated debug build and its corresponding source."""

from pathlib import Path
import hashlib
import shutil
import zipfile


ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
ARTIFACTS = ROOT / "artifacts"
SOURCE_INPUTS = (
    "AGENTS.md", "README.md", "LICENSE", "THIRD_PARTY_NOTICES.md", ".gitignore",
    "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew",
    "gradlew.bat", "app/build.gradle.kts", "app/src", "gradle", "scripts",
    "licenses", "docs", "artifacts/README.md",
)
# Only reviewed evidence is distributed. Never recursively copy device data.
EVIDENCE = (
    "unit-tests.xml", "build-validation.txt", "lint.txt", "apk-signature.txt",
    "device-ui.png", "event-validation.json", "owner-auth-phase1.txt",
    "owner-auth-background.txt", "owner-auth-screen-off.txt",
    "owner-auth-screen-off-long.txt", "notification-hidden-monitoring.txt",
    "notification-channel-after-reboot.txt", "reboot-auto-resume.txt",
    "threshold-events-device.txt",
    "review-stability-validation.txt", "instrumentation-review.txt",
    "presence-mapping-validation.txt",
)


def main():
    if not APK.is_file():
        raise SystemExit("Build first: ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug")
    notice = ROOT / "THIRD_PARTY_NOTICES.md"
    embedded = ROOT / "app/src/main/assets/licenses/THIRD_PARTY_NOTICES.md"
    if notice.read_bytes() != embedded.read_bytes():
        raise SystemExit("Synchronize the root and APK asset third-party notices, then rebuild.")
    with zipfile.ZipFile(APK) as apk:
        if apk.read("assets/licenses/THIRD_PARTY_NOTICES.md") != notice.read_bytes():
            raise SystemExit("APK contains old notices; rebuild before packaging.")

    files = []
    for name in SOURCE_INPUTS:
        path = ROOT / name
        if not path.exists():
            raise SystemExit(f"Missing source input: {name}")
        files.extend([path] if path.is_file() else sorted(
            p for p in path.rglob("*")
            if p.is_file() and p.name != ".DS_Store" and "__pycache__" not in p.parts
        ))
    for name in EVIDENCE:
        path = ROOT / "test-results" / name
        if path.is_file():
            files.append(path)

    ARTIFACTS.mkdir(exist_ok=True)
    for name in ("UWB-Pulse.apk", "UWB-Pulse-diagnostic.apk"):
        shutil.copyfile(APK, ARTIFACTS / name)
    archive = ARTIFACTS / "UWB-Pulse-source.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as output:
        for path in files:
            output.write(path, Path("UWB_Pulse") / path.relative_to(ROOT))
    with zipfile.ZipFile(archive) as output:
        bad = output.testzip()
        if bad:
            raise SystemExit(f"Archive CRC failed: {bad}")

    checks = []
    for name in ("UWB-Pulse.apk", "UWB-Pulse-diagnostic.apk", archive.name):
        digest = hashlib.sha256((ARTIFACTS / name).read_bytes()).hexdigest()
        checks.append(f"{digest}  {name}")
    (ARTIFACTS / "SHA256SUMS.txt").write_text("\n".join(checks) + "\n")
    print(f"Packaged APK and {len(files)} source/evidence files in {ARTIFACTS}")


if __name__ == "__main__":
    main()
