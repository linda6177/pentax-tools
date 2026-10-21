"""Integrate one independently compiled native UI Activity into an owner-supplied APK.

Nothing is decompiled or substituted in the original camera code. The binary
AndroidManifest.xml gets an extra launcher Activity while the original
TitleActivity stays declared as the onboarding entry point.

External tools: already-installed Android 35 zipalign and apksigner + JDK 17.
"""
from __future__ import annotations

import hashlib
import json
import os
import struct
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
APP = Path(r"D:\Download\OPPO\Image Sync.apk")
EXPECTED_SHA256 = "00756f0c0474c7f6ac0271b817effadf15e92362d49eab9d86cd0d6bba639442"
CLASS_NAME = "com.ricohimaging.imagesync.ModernHomeAct"
ORIGINAL_TITLE = "com.ricohimaging.imagesync.TitleActivity"
DEX = ROOT / "dist" / "modern-shell.dex"
DIST = ROOT / "dist"
BUILD = ROOT / "build"


def toolchain():
    for candidate in Path(r"D:\Desktop").iterdir():
        folder = candidate / "tools" / ".android-toolchain"
        if (folder / "sdk" / "build-tools" / "35.0.0" / "apksigner.bat").exists():
            return folder
    raise FileNotFoundError("Original Android build toolchain could not be found")


def u16(data, offset):
    return struct.unpack_from("<H", data, offset)[0]


def u32(data, offset):
    return struct.unpack_from("<I", data, offset)[0]


def put32(buffer, offset, number):
    struct.pack_into("<I", buffer, offset, number)


def chunks(document):
    if document[:2] != b"\x03\x00":
        raise ValueError("Expected binary Android XML")
    offset = 8
    while offset < len(document):
        kind, header, size = struct.unpack_from("<HHI", document, offset)
        if size < header or size < 8 or offset + size > len(document):
            raise ValueError(f"Invalid XML chunk at {offset}")
        yield offset, kind, header, size
        offset += size
    if offset != len(document):
        raise ValueError("Malformed binary Android XML length")


def decoded_strings(pool):
    _, hdr, total, num, style_count, flags, string_start, style_start = (
        struct.unpack_from("<HHIIIIII", pool, 0)
    )
    if hdr != 28 or style_count != 0 or flags & 0x100:
        raise ValueError("Input manifest string pool layout is unsupported")
    strings = []
    for i in range(num):
        offset = string_start + u32(pool, hdr + i * 4)
        n = u16(pool, offset)
        offset += 2
        if n & 0x8000:
            n = ((n & 0x7FFF) << 16) | u16(pool, offset)
            offset += 2
        strings.append(pool[offset:offset + n * 2].decode("utf-16le"))
    return strings


def extend_pool(pool, value):
    typ, hdr, total, num, styles, flags, start, sstart = (
        struct.unpack_from("<HHIIIIII", pool, 0)
    )
    if typ != 1 or styles or sstart or flags & 0x100:
        raise ValueError("Unexpected string pool format")
    if value in decoded_strings(pool):
        raise ValueError("Activity name unexpectedly present already")
    old_table = pool[hdr:start]
    old_data = pool[start:total]
    encoded = value.encode("utf-16le")
    utf16_size = len(encoded) // 2
    if utf16_size >= 0x8000:
        raise ValueError("String too long")
    append = struct.pack("<H", utf16_size) + encoded + b"\x00\x00"
    new_data = old_data + append
    new_data += b"\x00" * ((-len(new_data)) % 4)
    new_start = start + 4
    rebuilt = bytearray(pool[:hdr] + old_table
                        + struct.pack("<I", len(old_data)) + new_data)
    put32(rebuilt, 4, len(rebuilt))
    put32(rebuilt, 8, num + 1)
    put32(rebuilt, 20, new_start)
    return bytes(rebuilt), num


def activity_name_index(chunk, all_strings):
    # START_ELEMENT header size 16, ext starts at offset 16:
    # ns, name, attrStart, attrSize, attrCount, id, class, style
    if u16(chunk, 0) != 0x0102:
        return None
    attr_start, attr_size, count = struct.unpack_from("<HHH", chunk, 24)
    for j in range(count):
        offset = 16 + attr_start + j * attr_size
        attrname = u32(chunk, offset + 4)
        data_type = chunk[offset + 15]
        if attrname < len(all_strings) and all_strings[attrname] == "name" and data_type == 3:
            return offset + 8, offset + 16, u32(chunk, offset + 16)
    return None


def patch_launcher(original):
    old_chunks = list(chunks(original))
    pools = [(off, sz) for off, ty, _, sz in old_chunks if ty == 1]
    if len(pools) != 1:
        raise ValueError("Expected exactly one string pool")
    pool_off, pool_size = pools[0]
    strings = decoded_strings(original[pool_off:pool_off + pool_size])
    if strings.count(ORIGINAL_TITLE) != 1:
        raise ValueError("TitleActivity identity is ambiguous")

    title_start = title_end = None
    title_attr = None
    nesting = 0
    in_title = False
    for offset, kind, _, size in old_chunks:
        part = original[offset:offset + size]
        if kind == 0x0102:
            if in_title:
                nesting += 1
            else:
                attr = activity_name_index(part, strings)
                if attr and attr[2] == strings.index(ORIGINAL_TITLE):
                    title_start = offset
                    title_attr = attr
                    nesting = 0
                    in_title = True
        elif kind == 0x0103 and in_title:
            if nesting == 0:
                title_end = offset
                in_title = False
            else:
                nesting -= 1
    if title_start is None or title_end is None:
        raise ValueError("TitleActivity manifest boundary not found")

    start_size = u32(original, title_start + 4)
    end_size = u32(original, title_end + 4)
    old_start = original[title_start:title_start + start_size]
    old_end = original[title_end:title_end + end_size]
    launcher_body_start = title_start + start_size
    next_ty = u16(original, launcher_body_start)
    if next_ty != 0x0102:
        raise ValueError("TitleActivity has no child intent-filter")
    first_child_name = strings[u32(original, launcher_body_start + 20)]
    if first_child_name != "intent-filter":
        raise ValueError("Expected launcher intent filter as first child")

    new_pool, new_index = extend_pool(
        original[pool_off:pool_off + pool_size], CLASS_NAME
    )
    modern_start = bytearray(old_start)
    raw_off, data_off, old_idx = title_attr
    if u32(modern_start, raw_off) != 0xFFFFFFFF:
        put32(modern_start, raw_off, new_index)
    put32(modern_start, data_off, new_index)
    # Relocate the existing launcher intent filter to the new Activity:
    # <activity TitleActivity/> <activity ModernHomeAct><intent-filter .../>
    insertion = old_end + bytes(modern_start)
    updated = (original[:pool_off] + new_pool
               + original[pool_off + pool_size:launcher_body_start]
               + insertion
               + original[launcher_body_start:])
    updated = bytearray(updated)
    put32(updated, 4, len(updated))
    return bytes(updated)


def extend_pool_with_strings(pool, values):
    """Append several strings; returns (new_pool, {value: index})."""
    current = pool
    mapping = {}
    for value in values:
        existing = decoded_strings(current)
        if value in existing:
            mapping[value] = existing.index(value)
            continue
        new_pool, index = extend_pool(current, value)
        mapping[value] = index
        current = new_pool
    return current, mapping


def manifest_root_bounds(document, parts):
    """Returns (start_chunk_offset, end_chunk_offset) of the root element."""
    depth = 0
    root_start = root_end = None
    for offset, kind, _, size in parts:
        if kind == 0x0102:
            if depth == 0:
                root_start = offset
            depth += 1
        elif kind == 0x0103:
            depth -= 1
            if depth == 0 and root_start is not None and root_end is None:
                root_end = offset
    if root_start is None or root_end is None:
        raise ValueError("manifest root element not found")
    return root_start, root_end


def add_uses_permissions(manifest, permissions):
    """Insert <uses-permission android:name="..."/> elements into the binary XML.

    The modern UI needs normal install-time permissions the original app never
    declared (currently TRANSMIT_IR for the O-RC1 infrared remote). Inserting a
    balanced START_ELEMENT/END_ELEMENT pair right after the root element keeps
    every existing chunk untouched.
    """
    if not permissions:
        return manifest
    parts = list(chunks(manifest))
    pool_off, pool_size = next((off, sz) for off, ty, _, sz in parts if ty == 1)
    pool = manifest[pool_off:pool_off + pool_size]
    strings = decoded_strings(pool)
    wanted = ["uses-permission", "name"] + [
        value for value in permissions
    ]
    new_pool, index = extend_pool_with_strings(pool, wanted)
    attr_name_index = strings.index("name") if "name" in strings else index["name"]
    if "uses-permission" in strings:
        tag_index = strings.index("uses-permission")
    else:
        tag_index = index["uses-permission"]

    root_start, root_end = manifest_root_bounds(manifest, parts)
    root_line = u32(manifest, root_start + 8)
    root_size = u32(manifest, root_start + 4)

    inserted = bytearray()
    for permission in permissions:
        value_index = index[permission]
        start = bytearray()
        start += struct.pack("<HHI", 0x0102, 16, 56)     # node + attrExt + 1 attr
        start += struct.pack("<I", root_line)            # line number
        start += struct.pack("<I", 0xFFFFFFFF)           # comment
        start += struct.pack("<II", 0xFFFFFFFF, tag_index)
        start += struct.pack("<HHHHHH", 20, 20, 1, 0, 0, 0)
        start += struct.pack("<II", 0xFFFFFFFF, attr_name_index)
        start += struct.pack("<I", value_index)          # raw value
        start += struct.pack("<HBBI", 8, 0, 0x03, value_index)  # typed string
        inserted += start
        end = bytearray()
        end += struct.pack("<HHI", 0x0103, 16, 24)
        end += struct.pack("<I", root_line)
        end += struct.pack("<I", 0xFFFFFFFF)
        end += struct.pack("<II", 0xFFFFFFFF, tag_index)
        inserted += end

    insertion_at = root_start + root_size
    updated = bytearray(manifest[:pool_off] + new_pool
                        + manifest[pool_off + pool_size:insertion_at]
                        + bytes(inserted)
                        + manifest[insertion_at:])
    put32(updated, 4, len(updated))
    return bytes(updated)


def run(*args):
    result = subprocess.run(
        [str(a) for a in args], capture_output=True, text=True, errors="replace"
    )
    if result.returncode:
        raise RuntimeError(f"{args[0]} failed: {result.stdout}\n{result.stderr}")
    return (result.stdout + result.stderr).strip()


def read_back_manifest(manifest):
    parts = list(chunks(manifest))
    pool_off, _, _, pool_size = next(c for c in parts if c[1] == 1)
    ss = decoded_strings(manifest[pool_off:pool_off + pool_size])
    records = []
    depth = 0
    for off, kind, _, size in parts:
        block = manifest[off:off + size]
        if kind == 0x0102:
            name = ss[u32(block, 20)]
            if name == "activity":
                attr = activity_name_index(block, ss)
                if attr:
                    records.append((ss[attr[2]], off))
            depth += 1
        elif kind == 0x0103:
            depth -= 1
            if depth < 0:
                raise ValueError("Manifest nesting became invalid")
    if depth != 0:
        raise ValueError("Unbalanced binary manifest")
    counts = {x: sum(name == x for name, _ in records)
              for x in (CLASS_NAME, ORIGINAL_TITLE)}
    if any(v != 1 for v in counts.values()):
        raise ValueError(f"Incorrect Activity declarations: {counts}")
    return records


def main():
    DIST.mkdir(exist_ok=True)
    BUILD.mkdir(exist_ok=True)
    if not APP.is_file():
        raise FileNotFoundError(f"Original APK not found: {APP}")
    if hashlib.sha256(APP.read_bytes()).hexdigest() != EXPECTED_SHA256:
        raise RuntimeError("Original APK sha256 did not match the user-provided sample")
    if not DEX.is_file() or DEX.read_bytes()[:3] != b"dex":
        raise RuntimeError("Compile the modern UI DEX with build.ps1 first")

    unsigned = BUILD / "unsigned.apk"
    aligned = BUILD / "aligned.apk"
    output = DIST / "ImageSync-Modern-2.1.32.apk"
    with zipfile.ZipFile(APP) as source, zipfile.ZipFile(unsigned, "w") as target:
        members = set(source.namelist())
        if "classes3.dex" in members:
            raise RuntimeError("Input already has classes3.dex; can't append unchanged")
        new_manifest = patch_launcher(source.read("AndroidManifest.xml"))
        new_manifest = add_uses_permissions(new_manifest, [
            "android.permission.TRANSMIT_IR",
            "android.permission.VIBRATE",
            "android.permission.POST_NOTIFICATIONS",
        ])
        read_back_manifest(new_manifest)
        for info in source.infolist():
            upper = info.filename.upper()
            if (upper.startswith("META-INF/") and
                    (upper.endswith(".RSA") or upper.endswith(".SF")
                     or upper.endswith(".DSA") or upper.endswith(".EC")
                     or upper == "META-INF/MANIFEST.MF")):
                continue
            data = new_manifest if info.filename == "AndroidManifest.xml" else source.read(info)
            target.writestr(info, data)
        target.writestr("classes3.dex", DEX.read_bytes(), compress_type=zipfile.ZIP_DEFLATED)


    base = toolchain()
    zipalign = base / "sdk" / "build-tools" / "35.0.0" / "zipalign.exe"
    apksigner = base / "sdk" / "build-tools" / "35.0.0" / "lib" / "apksigner.jar"
    java = next((base / "jdk").glob("jdk*")) / "bin" / "java.exe"
    keytool = next((base / "jdk").glob("jdk*")) / "bin" / "keytool.exe"
    key = BUILD / "development.keystore"
    if not key.exists():
        run(keytool, "-genkeypair", "-keystore", key, "-storepass", "android",
            "-keypass", "android", "-alias", "imagesyncmodern", "-keyalg",
            "RSA", "-keysize", "3072", "-validity", "3650",
            "-dname", "CN=Image Sync Local UI Development")

    run(zipalign, "-P", "16", "-f", "4", unsigned, aligned)
    run(zipalign, "-c", "-P", "16", "-v", "4", aligned)
    if output.exists():
        output.unlink()
    run(java, "-jar", apksigner, "sign", "--ks", key,
        "--ks-pass", "pass:android", "--ks-key-alias", "imagesyncmodern",
        "--key-pass", "pass:android", "--v1-signing-enabled", "true",
        "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
        "--out", output, aligned)
    verification = run(java, "-jar", apksigner, "verify", "--verbose",
                       "--print-certs", output)
    with zipfile.ZipFile(output) as result:
        manifest_entries = [x for x in result.namelist() if x == "AndroidManifest.xml"]
        if len(manifest_entries) != 1 or "classes3.dex" not in result.namelist():
            raise RuntimeError("Signed APK lost the replacement manifest or DEX")
        read_back_manifest(result.read("AndroidManifest.xml"))
        unchanged = ["classes.dex", "classes2.dex", "resources.arsc"]
        with zipfile.ZipFile(APP) as source:
            for name in unchanged:
                if result.read(name) != source.read(name):
                    raise RuntimeError(f"Original file unexpectedly changed: {name}")
    report = {
        "input_apk": str(APP),
        "input_sha256": EXPECTED_SHA256,
        "output_apk": str(output),
        "output_sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
        "output_bytes": output.stat().st_size,
        "new_activity": CLASS_NAME,
        "legacy_activities_preserved": True,
        "modern_screens": ["home", "gallery", "image viewer", "remote capture", "settings"],
        "added_permissions": ["android.permission.TRANSMIT_IR", "android.permission.VIBRATE",
                               "android.permission.POST_NOTIFICATIONS"],
        "original_dex_and_resources_byte_identical": True,
        "signing": "new development key; not compatible with in-place official updates",
        "sign_verify": verification,
        "phone_verified": False,
        "camera_verified": False,
        "phase": "single-activity modern UI (home/gallery/viewer/remote/settings) on the original Ricoh SDK transport",
    }
    (DIST / "integration-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
