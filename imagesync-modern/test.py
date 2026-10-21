"""Offline assertions for the signed Image Sync modern-UI APK.

These checks are static: no device or camera is required. They prove that the
auxiliary DEX (a) contains only the modern UI, (b) links exclusively against
classes that really exist in the original shipped DEX / Android framework and
(c) does not disturb any original byte in the repackaged APK.
"""

import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path

from integrate import (
    APP, CLASS_NAME, DEX, DIST, EXPECTED_SHA256, ORIGINAL_TITLE, ROOT,
    read_back_manifest, toolchain,
)

OUTPUT_NAME = "ImageSync-Modern-2.1.32.apk"

# Screens and infrastructure that must exist in the shipped auxiliary DEX.
EXPECTED_CLASSES = [
    "com/ricohimaging/imagesync/ModernHomeAct",
    "com/ricohimaging/imagesync/modern/ShellActivity",
    "com/ricohimaging/imagesync/modern/CameraBridge",
    "com/ricohimaging/imagesync/modern/UiKit",
    "com/ricohimaging/imagesync/modern/Theme",
    "com/ricohimaging/imagesync/modern/BottomBar",
    "com/ricohimaging/imagesync/modern/HomeScreen",
    "com/ricohimaging/imagesync/modern/GalleryScreen",
    "com/ricohimaging/imagesync/modern/ImageViewerScreen",
    "com/ricohimaging/imagesync/modern/RemoteScreen",
    "com/ricohimaging/imagesync/modern/IrRemote",
]

FORBIDDEN_CLASS_PREFIXES = (
    "com/ricoh/camera/sdk/",
    "androidx/",
    "com/google/",
    "kotlin/",
    "com/ricohimaging/imagesync/SvApplication",
)


def invoke(*args):
    proc = subprocess.run(
        [str(x) for x in args], capture_output=True, text=True, errors="replace"
    )
    if proc.returncode != 0:
        raise AssertionError(f"{args[0]} failed: {proc.stdout}\n{proc.stderr}")
    return proc.stdout + proc.stderr


# Types injected only by Java 8 desugaring; d8 rewrites these lambdas away, so
# they never need to resolve on the device.
DESUGAR_ONLY = {
    "java/lang/invoke/LambdaMetafactory",
    "java/lang/invoke/MethodHandles$Lookup",
    "java/lang/invoke/MethodType",
    "java/lang/invoke/MethodHandle",
    "java/lang/invoke/SerializedLambda",
    "java/lang/invoke/StringConcatFactory",
}


def descriptor_to_name(descriptor):
    name = descriptor.strip().strip('"').strip("'")
    while name.startswith("["):
        name = name[1:]
    if name.startswith("L") and name.endswith(";"):
        name = name[1:-1]
    return name


def dex_defined_classes(dexdump, *dex_files):
    text = invoke(dexdump, *dex_files)
    return {descriptor_to_name(m) for m in
            re.findall(r"Class descriptor\s+:\s+'([^']+)'", text)}


def dex_members(dexdump, *dex_files):
    """Map class descriptor -> (method keys, field keys) defined in the DEX."""
    text = invoke(dexdump, *dex_files)
    members = {}
    supers = {}
    current = None
    section = None
    pending_name = None
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("Class descriptor"):
            descriptor = stripped.split("'")[1]
            current = descriptor_to_name(descriptor)
            members.setdefault(current, [set(), set()])
            section = None
            continue
        if current is None:
            continue
        if stripped.startswith("Static fields") or stripped.startswith("Instance fields"):
            section = "field"
            continue
        if stripped.startswith("Direct methods") or stripped.startswith("Virtual methods"):
            section = "method"
            continue
        if stripped.startswith("Superclass"):
            section = None
            value = stripped.split("'")
            if len(value) > 1:
                supers[current] = descriptor_to_name(value[1])
            continue
        if stripped.startswith("Interfaces"):
            section = None
            continue
        if section and stripped.startswith("name"):
            pending_name = stripped.split("'")[1]
            continue
        if section and stripped.startswith("type") and pending_name is not None:
            value = stripped.split("'")[1]
            members[current][0 if section == "method" else 1].add((pending_name, value))
            pending_name = None
            continue
    return ({name: (methods, fields) for name, (methods, fields) in members.items()}, supers)


def javap_member_refs(javap, classes_dir, class_file):
    """Collect (owner, name, descriptor, kind) symbolic member references."""
    binary = str(class_file.relative_to(classes_dir))[:-len(".class")].replace("\\", ".")
    text = invoke(javap, "-p", "-v", "-classpath", classes_dir, binary)
    refs = set()
    pattern = re.compile(
        r"=\s*(Methodref|InterfaceMethodref|Fieldref)\s+#\d+\.#\d+\s+//\s+"
        r"(\S+?)\.(\S+):(\S+)")
    for kind, owner, name, descriptor in pattern.findall(text):
        name = name.strip('"').strip("'")
        refs.add((owner, name, descriptor, "method" if "Method" in kind else "field"))
    return refs


def javap_classes(javap, classes_dir, class_file):
    binary = str(class_file.relative_to(classes_dir))[:-len(".class")].replace("\\", ".")
    text = invoke(javap, "-p", "-v", "-classpath", classes_dir, binary)
    referenced = set()
    for raw in re.findall(r"=\s*Class\s+#\d+\s+//\s+(\S+)", text):
        referenced.add(descriptor_to_name(raw))
    return referenced


def android_jar_classes(android_jar):
    names = set()
    with zipfile.ZipFile(android_jar) as jar:
        for entry in jar.namelist():
            if entry.endswith(".class"):
                names.add(entry[:-len(".class")])
    return names


def main():
    signed = DIST / OUTPUT_NAME
    assert signed.exists(), "Signed APK missing"
    assert hashlib.sha256(APP.read_bytes()).hexdigest() == EXPECTED_SHA256
    tools = toolchain() / "sdk" / "build-tools" / "35.0.0"
    aapt = tools / "aapt2.exe"
    align = tools / "zipalign.exe"
    dexdump = tools / "dexdump.exe"
    jdk = next((toolchain() / "jdk").glob("jdk*"))
    java = jdk / "bin" / "java.exe"
    javap = jdk / "bin" / "javap.exe"
    apksigner = tools / "lib" / "apksigner.jar"
    android_jar = toolchain() / "sdk" / "platforms" / "android-35" / "android.jar"

    # ---- 1. DEX identity: only our classes are defined ----------------------
    dex_bytes = DEX.read_bytes()
    assert dex_bytes[:3] == b"dex", "Auxiliary DEX is not a dex file"
    our_classes = dex_defined_classes(dexdump, DEX)
    for name in our_classes:
        for prefix in FORBIDDEN_CLASS_PREFIXES:
            assert not name.startswith(prefix), f"original/stub class inside DEX: {name}"
    for expected in EXPECTED_CLASSES:
        assert expected in our_classes, f"missing modern class: {expected}"
    assert len(our_classes) >= 60, "unexpectedly small modern UI dex"

    # ---- 2. Static link check ----------------------------------------------
    # Every type our classes reference must exist in our own DEX, the original
    # shipped DEX, the compile-time stubs, or the Android framework jar.
    dex_dir = ROOT / "build" / "dex"
    dex_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(APP) as source:
        for entry in ("classes.dex", "classes2.dex"):
            target = dex_dir / entry
            if not target.exists():
                target.write_bytes(source.read(entry))
    original_classes = dex_defined_classes(
        dexdump, dex_dir / "classes.dex", dex_dir / "classes2.dex"
    )
    stub_classes = set()
    stub_dir = ROOT / "build" / "stubclasses"
    assert stub_dir.exists(), "stub classes missing; run build.ps1 first"
    for class_file in stub_dir.rglob("*.class"):
        relative = class_file.relative_to(stub_dir).with_suffix("")
        stub_classes.add(str(relative).replace("\\", "/"))
    framework_classes = android_jar_classes(android_jar)
    allowed = our_classes | original_classes | stub_classes | framework_classes
    referenced = set()
    classes_dir = ROOT / "build" / "classes"
    for class_file in classes_dir.rglob("*.class"):
        referenced |= javap_classes(javap, classes_dir, class_file)
    primitives = {'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z'}
    missing = sorted(name for name in referenced
                     if name not in allowed
                     and name not in primitives
                     and name not in DESUGAR_ONLY
                     and not name.startswith('java/lang/invoke/'))
    assert not missing, f"unresolvable class references: {missing[:8]}"

    # Member level: every method/field we call on an ORIGINAL class must exist
    # with the exact descriptor, or the DEX would fail at runtime with
    # NoSuchMethodError / NoSuchFieldError.
    original_members, original_supers = dex_members(
        dexdump, dex_dir / "classes.dex", dex_dir / "classes2.dex")
    member_refs = set()
    for class_file in classes_dir.rglob("*.class"):
        member_refs |= javap_member_refs(javap, classes_dir, class_file)
    def resolve(owner, name, descriptor, kind):
        """Walk the DEX-defined superclass chain; accept framework parents."""
        seen = set()
        while owner in original_members and owner not in seen:
            seen.add(owner)
            methods, fields = original_members[owner]
            if (name, descriptor) in (methods if kind == "method" else fields):
                return True
            owner = original_supers.get(owner)
        return owner is not None and owner not in original_members

    unresolved = []
    for owner, name, descriptor, kind in member_refs:
        if owner not in original_members:
            continue
        if not resolve(owner, name, descriptor, kind):
            unresolved.append(f"{owner}.{name}{descriptor}")
    assert not unresolved, f"unresolvable SDK members: {sorted(unresolved)[:8]}"

    # ---- 3. Repackaged APK integrity ---------------------------------------
    with zipfile.ZipFile(APP) as original, zipfile.ZipFile(signed) as result:
        original_names = original.namelist()
        names = result.namelist()
        assert len(names) == len(set(names)), "Duplicate ZIP paths"
        assert [n for n in names if n == "classes3.dex"] == ["classes3.dex"]
        assert result.read("classes3.dex") == dex_bytes

        assert b"ModernHomeAct" in dex_bytes
        assert b"ModernMainOverlay" not in dex_bytes
        unchanged = [n for n in original_names if n not in
                     {"AndroidManifest.xml", "META-INF/MANIFEST.MF"}
                     and not (n.upper().startswith("META-INF/") and
                              n.upper().endswith((".RSA", ".SF", ".EC", ".DSA")))]
        for name in unchanged:
            assert original.read(name) == result.read(name), f"Changed {name}"
        manifest = result.read("AndroidManifest.xml")
        activities = read_back_manifest(manifest)
        declared = [name for name, _ in activities]
        assert CLASS_NAME in declared
        assert ORIGINAL_TITLE in declared
        assert "com.ricohimaging.imagesync.MainActivity" in declared
        assert "com.ricohimaging.imagesync.ShootingActivity" in declared
        assert "com.ricohimaging.imagesync.ImageViewActivity" in declared

    # ---- 4. Manifest packaging and signing --------------------------------
    badges = invoke(aapt, "dump", "badging", signed)
    assert f"launchable-activity: name='{CLASS_NAME}'" in badges
    assert "versionName='2.1.32'" in badges
    assert "targetSdkVersion:'36'" in badges
    assert "minSdkVersion:'23'" in badges
    assert len(re.findall("launchable-activity:", badges)) == 1
    assert "uses-permission: name='android.permission.TRANSMIT_IR'" in badges, \
        "infrared permission missing"
    assert "uses-permission: name='android.permission.VIBRATE'" in badges, \
        "vibration permission missing"
    assert "uses-permission: name='android.permission.POST_NOTIFICATIONS'" in badges, \
        "notification permission missing" 
    invoke(align, "-c", "-P", "16", "4", signed)
    signing = invoke(java, "-jar", apksigner, "verify", "--verbose", signed)
    for mode in ("v1", "v2", "v3"):
        assert re.search(r"Verified using " + mode + r" scheme .*: true", signing)

    report = {
        "static_checks_passed": True,
        "original_apk": str(APP),
        "input_sha256": EXPECTED_SHA256,
        "output_apk": str(signed),
        "apk_sha256": hashlib.sha256(signed.read_bytes()).hexdigest(),
        "original_entries_checked_unchanged": len(unchanged),
        "new_dex_bytes": len(dex_bytes),
        "modern_classes_defined": len(our_classes),
        "expected_screens_present": True,
        "static_link_classes_checked": len(referenced),
        "static_link_sdk_members_checked": len(member_refs),
        "static_link_unresolved": 0,
        "infrared_permission_declared": True,
        "vibration_permission_declared": True,
        "activities_original_preserved": True,
        "launcher": CLASS_NAME,
        "zipalign_16k": True,
        "v1_v2_v3_verified": True,
        "device_tested": False,
        "camera_tested": False,
        "warning": "Signing certificate changed: uninstall the official app before installing; "
                   "camera behaviour must still be validated on hardware.",
    }
    (DIST / "test-report.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    print(json.dumps(report, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
