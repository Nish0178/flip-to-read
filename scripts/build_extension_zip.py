#!/usr/bin/env python3
"""
Flip to Read — Browser Extension Production ZIP Builder
Packages extension/dist into a clean, production-ready ZIP archive for Chrome/Edge/Brave:
flip-to-read-extension.zip

Output destination:
java-app/src/main/resources/static/flip-to-read-extension.zip

Requirements:
- Python 3.8+ (standard library only, zero external dependencies)
- Node.js & npm (for building extension/dist if needed)
"""

import os
import sys
import json
import hashlib
import zipfile
import argparse
import subprocess
from pathlib import Path


IGNORED_FILES = {
    ".ds_store",
    "thumbs.db",
    "desktop.ini",
}

IGNORED_EXTENSIONS = {
    ".tmp",
    ".bak",
    ".swp",
}


def compute_sha256(file_path: Path) -> str:
    h = hashlib.sha256()
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def build_extension(ext_dir: Path):
    print(f"[+] Building extension in {ext_dir}...")
    use_shell = os.name == "nt"
    cmd = ["npm", "run", "build"]
    result = subprocess.run(cmd, cwd=ext_dir, shell=use_shell)
    if result.returncode != 0:
        raise RuntimeError(f"npm run build failed with code {result.returncode}")


def validate_dist_files(dist_dir: Path) -> dict:
    manifest_path = dist_dir / "manifest.json"
    if not manifest_path.is_file():
        raise FileNotFoundError(f"manifest.json not found in {dist_dir}")

    with open(manifest_path, "r", encoding="utf-8") as f:
        manifest = json.load(f)

    if manifest.get("manifest_version") != 3:
        raise ValueError(f"Expected manifest_version 3, got: {manifest.get('manifest_version')}")

    # Verify key entrypoints referenced in manifest
    # Background service worker
    sw = manifest.get("background", {}).get("service_worker")
    if sw and not (dist_dir / sw).is_file():
        raise FileNotFoundError(f"Service worker referenced in manifest not found: {sw}")

    # Content scripts
    for cs in manifest.get("content_scripts", []):
        for js_file in cs.get("js", []):
            if not (dist_dir / js_file).is_file():
                raise FileNotFoundError(f"Content script referenced in manifest not found: {js_file}")

    # Action popup
    popup = manifest.get("action", {}).get("default_popup")
    if popup and not (dist_dir / popup).is_file():
        raise FileNotFoundError(f"Popup HTML referenced in manifest not found: {popup}")

    # Icons
    for size, icon_path in manifest.get("icons", {}).items():
        if not (dist_dir / icon_path).is_file():
            raise FileNotFoundError(f"Icon ({size}) referenced in manifest not found: {icon_path}")

    return manifest


def package_extension(dist_dir: Path, output_zip: Path) -> dict:
    manifest = validate_dist_files(dist_dir)
    version = manifest.get("version", "1.0.0")
    name = manifest.get("name", "Flip to Read")

    output_zip.parent.mkdir(parents=True, exist_ok=True)

    # Collect files
    files_to_pack = []
    for root, _, files in os.walk(dist_dir):
        for file in sorted(files):
            file_lower = file.lower()
            if file_lower in IGNORED_FILES:
                continue
            if any(file_lower.endswith(ext) for ext in IGNORED_EXTENSIONS):
                continue

            full_path = Path(root) / file
            rel_path = full_path.relative_to(dist_dir).as_posix()
            files_to_pack.append((full_path, rel_path))

    if not files_to_pack:
        raise RuntimeError(f"No files found in {dist_dir} to package")

    # Write ZIP
    total_uncompressed = 0
    with zipfile.ZipFile(output_zip, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for full_path, rel_path in files_to_pack:
            zf.write(full_path, arcname=rel_path)
            total_uncompressed += full_path.stat().st_size

    # Verify ZIP integrity
    with zipfile.ZipFile(output_zip, "r") as zf:
        bad_file = zf.testzip()
        if bad_file:
            raise RuntimeError(f"Corrupt file in created ZIP: {bad_file}")

        zip_namelist = zf.namelist()
        if "manifest.json" not in zip_namelist:
            raise RuntimeError("manifest.json is NOT at the root of the ZIP file!")

        # Verify manifest inside the ZIP is readable
        with zf.open("manifest.json") as mf:
            inner_manifest = json.load(mf)
            if inner_manifest.get("version") != version:
                raise RuntimeError("Manifest version mismatch inside ZIP")

    zip_size = output_zip.stat().st_size
    sha256 = compute_sha256(output_zip)

    return {
        "name": name,
        "version": version,
        "file_count": len(files_to_pack),
        "uncompressed_bytes": total_uncompressed,
        "zip_bytes": zip_size,
        "sha256": sha256,
        "files": [r for _, r in files_to_pack],
    }


def main():
    parser = argparse.ArgumentParser(description="Package Flip to Read Chrome extension into production ZIP")
    parser.add_argument("--rebuild", action="store_true", help="Always run npm run build before packaging")
    parser.add_argument("--no-build", action="store_true", help="Skip npm run build even if dist is missing")
    parser.add_argument("--dist-dir", type=str, default=None, help="Path to extension dist directory")
    parser.add_argument("--output-zip", type=str, default=None, help="Path to output ZIP file")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parent.parent
    ext_dir = repo_root / "extension"
    dist_dir = Path(args.dist_dir) if args.dist_dir else ext_dir / "dist"
    static_dir = repo_root / "java-app" / "src" / "main" / "resources" / "static"
    output_zip = Path(args.output_zip) if args.output_zip else static_dir / "flip-to-read-extension.zip"

    print("==================================================")
    print("  Flip to Read — Extension Production ZIP Builder")
    print("==================================================")

    # 1. Check if build is needed
    manifest_path = dist_dir / "manifest.json"
    needs_build = args.rebuild or (not manifest_path.is_file() and not args.no_build)
    if needs_build:
        build_extension(ext_dir)

    # 2. Package and verify
    print(f"[+] Packaging from: {dist_dir}")
    print(f"[+] Output target:  {output_zip}")

    stats = package_extension(dist_dir, output_zip)

    # Sync to java-app/target/classes/static if it exists
    target_static = repo_root / "java-app" / "target" / "classes" / "static"
    if target_static.is_dir():
        import shutil
        target_zip = target_static / output_zip.name
        shutil.copy2(output_zip, target_zip)
        print(f"[+] Synced archive to target classes: {target_zip}")

    print("\n[+] Extension Archive Verified Successfully:")
    print(f"    Extension:           {stats['name']} v{stats['version']}")
    print(f"    Total Files:         {stats['file_count']}")
    print(f"    Uncompressed Size:   {stats['uncompressed_bytes']:,} bytes ({stats['uncompressed_bytes'] / 1024:.1f} KB)")
    print(f"    ZIP Archive Size:    {stats['zip_bytes']:,} bytes ({stats['zip_bytes'] / 1024:.1f} KB)")
    ratio = (1.0 - (stats['zip_bytes'] / max(1, stats['uncompressed_bytes']))) * 100
    print(f"    Compression Ratio:   {ratio:.1f}% space saved")
    print(f"    SHA-256 Checksum:    {stats['sha256']}")
    print(f"    Spring Static URL:   /flip-to-read-extension.zip")

    print("\n[+] Package File Structure (all relative to ZIP root):")
    for f in stats["files"]:
        print(f"    - {f}")

    print("\n==================================================")
    print("  BUILD COMPLETE: Production ZIP ready for distribution")
    print("==================================================")


if __name__ == "__main__":
    main()
