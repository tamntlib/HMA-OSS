#!/usr/bin/env python3
"""Run real service decisions and hook callbacks on the JVM with Android fakes.

Requires Python 3, JDK 21 and kotlinc 2.3.21 on PATH. No Android SDK is needed.
The Android framework/Binder boundaries are faked; this is not a device test.
"""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "zygote/src/main/java/org/frknkrc44/hma_oss/zygote"


def body(source, marker):
    start = source.index("{", source.index(marker))
    depth = 1
    end = start + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start + 1:end - 1]


def service_method(source, name):
    marker = f"fun {name}("
    start = source.index(marker)
    opening = source.index("{", start)
    return source[start:opening + 1] + body(source, marker) + "}"


def main():
    service_source = (SOURCE / "service/HMAService.kt").read_text()
    methods = [service_method(service_source, name) for name in (
        "shouldHide", "shouldHideActivityLaunch",
    )]
    if "fun shouldHideManagerByDefault(" in service_source:
        methods.append(service_method(service_source, "shouldHideManagerByDefault"))
    constants = (ROOT / "common/src/main/java/icu/nullptr/hidemyapplist/common/Constants.kt").read_text()
    constants = constants[constants.index("object Constants"):]
    fixture = (ROOT / "tests/manager_visibility.kt").read_text()
    generated = fixture.replace("// SERVICE_METHODS", "\n".join(methods))
    package_source = (SOURCE / "util/PackageManagerUtils.kt").read_text()
    generated = generated.replace("// LAUNCHER_METHOD", service_method(package_source, "isLauncherPackage"))
    activity_source = (SOURCE / "hook/ActivityHook.kt").read_text()
    callback = body(activity_source, '"applyPostResolutionFilter"').split("->", 1)[1]
    generated = generated.replace("// ACTIVITY_CALLBACK", callback)
    pms_source = (SOURCE / "hook/PmsHookTargetBase.kt").read_text()
    generated = generated.replace("// PMS_METHOD", service_method(pms_source, "applyPackageHiding"))
    generated += "\n" + constants
    with tempfile.TemporaryDirectory(prefix="hma-visibility-") as directory:
        directory = Path(directory)
        source = directory / "ManagerVisibilityTest.kt"
        source.write_text(generated)
        jar = directory / "tests.jar"
        subprocess.run([os.environ.get("KOTLINC", "kotlinc"), str(source),
                        "-nowarn", "-include-runtime", "-d", str(jar)], check=True)
        subprocess.run(["java", "-jar", str(jar)], check=True)


if __name__ == "__main__":
    main()
