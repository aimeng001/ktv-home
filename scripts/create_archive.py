"""Packages official project functional files into a clean distribution zip archive.

Excludes caches, temporary build artifacts, and non-essential local files.
"""

from pathlib import Path
import json
import os
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent

EXCLUDE_DIRS = {
    ".git",
    ".gradle",
    ".gradle-local",
    ".gradle-user-home",
    ".android-local",
    ".android-user-home",
    ".maven-local",
    ".m2-local",
    ".workbuddy",
    ".release-secrets",
    ".idea",
    ".vscode",
    ".kotlin",
    "ci-runtime",
    "tasks",
    "node_modules",
    "target",
    "bin",
    "obj",
    "staged",
    "build",
    "__pycache__",
    ".pytest_cache",
    ".vite",
    "postgres",
    "postgres-dev",
    "sample-music",
    "music",
    "source-music",
    "data",
    "scratch",
}

EXCLUDE_FILES = {
    "home-ktv.zip",
    "ktv-home.zip",
    "KTV_30.2.2.apk",
    "AGENTS.md",
    "PLAN.md",
    "TASK_STATE.md",
    "KTV_AGENT_PROMPTS.txt",
    "整改方案.md",
    "整改方案2.md",
    "整改方案_融合审核版.md",
    "安卓端重做整改方案_融合审核版.md",
    "软件崩溃.md",
    "问题清单.md",
    "整改计划.md",
    ".DS_Store",
    "local.properties",
}

EXCLUDE_EXTENSIONS = {
    ".pyc",
    ".pyo",
    ".class",
    ".log",
    ".tmp",
    ".suo",
    ".user",
}


ALLOWED_ROOT_FILES = {
    ".dockerignore",
    ".env.example",
    ".gitattributes",
    ".gitignore",
    "docker-compose.yml",
    "docker-compose.ci.yml",
    "docker-compose.dev.yml",
    "docker-compose.hardware.yml",
    "docker-compose.nas.yml",
    "docker-compose.prebuilt.yml",
    "docker-compose.rockchip.yml",
    "LICENSE",
    "README.md",
    "README_EN.md",
    "ARCHITECTURE.md",
    "REQUIREMENTS.md",
}

EXPECTED_TV_APPLICATION_ID = "com.homektv.tv"
APK_BADGING_PATTERN = re.compile(
    r"^package:\s+name='(?P<application_id>[^']+)'"
    r"(?:\s+versionCode='[^']*')?"
    r"(?:\s+versionName='(?P<version_name>[^']*)')?",
    re.MULTILINE,
)


def _release_version_name(release_apk_dir: Path) -> str | None:
    metadata = release_apk_dir / "output-metadata.json"
    if not metadata.is_file():
        return None
    try:
        payload = json.loads(metadata.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    for element in payload.get("elements", []):
        value = element.get("versionName")
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


def _aapt_command() -> list[str] | None:
    """Find an Android build-tools command without depending on one OS PATH layout."""

    for name in ("aapt", "aapt2"):
        command = shutil.which(name)
        if command:
            return [command]

    for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        sdk_root = os.environ.get(variable)
        if not sdk_root:
            continue
        build_tools = Path(sdk_root) / "build-tools"
        if not build_tools.is_dir():
            continue
        for directory in sorted(build_tools.iterdir(), reverse=True):
            if not directory.is_dir():
                continue
            for name in ("aapt.exe", "aapt", "aapt2.exe", "aapt2"):
                candidate = directory / name
                if candidate.is_file():
                    return [str(candidate)]
    return None


def parse_apk_badging(output: str) -> tuple[str, str]:
    match = APK_BADGING_PATTERN.search(output)
    if not match:
        raise ValueError("APK manifest badging did not contain an application identity")
    return match.group("application_id"), match.group("version_name") or ""


def validate_release_apk(path: Path, version_name: str, abi: str) -> None:
    """Fail closed unless an APK declares the release application identity."""

    lower_name = path.name.lower()
    if "debug" in lower_name or "unsigned" in lower_name:
        raise ValueError(f"refusing debug or unsigned APK: {path.name}")
    if abi not in path.name:
        raise ValueError(f"APK ABI does not match its staging slot: {path.name}")

    command = _aapt_command()
    if command is None:
        raise RuntimeError("cannot validate APK manifest: Android build-tools (aapt/aapt2) not found")
    try:
        result = subprocess.run(
            [*command, "dump", "badging", str(path)],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise RuntimeError(f"cannot validate APK manifest: {error}") from error
    if result.returncode != 0:
        raise ValueError(f"aapt failed while validating {path.name}: {result.stderr.strip()}")

    application_id, apk_version_name = parse_apk_badging(result.stdout)
    if application_id != EXPECTED_TV_APPLICATION_ID:
        raise ValueError(
            f"APK application id mismatch: {path.name} declares {application_id}, "
            f"expected {EXPECTED_TV_APPLICATION_ID}"
        )
    if apk_version_name != version_name:
        raise ValueError(
            f"APK version mismatch: {path.name} declares {apk_version_name}, expected {version_name}"
        )


def prepare_dist_apks(
    *,
    release_apk_dir: Path | None = None,
    dist_tv: Path | None = None,
    version_name: str | None = None,
):
    """Copy signed release APKs to ``dist/tv-apk``.

    Debug APKs are deliberately ignored: their application id and signing
    certificate cannot be used for upgrades of an installed release build.
    """
    dist_tv = dist_tv or (ROOT / "dist" / "tv-apk")
    dist_tv.mkdir(parents=True, exist_ok=True)
    release_apk_dir = release_apk_dir or (
        ROOT / "android-tv" / "app" / "build" / "outputs" / "apk" / "release"
    )
    version_name = (
        version_name
        or os.environ.get("KTV_TV_VERSION_NAME")
        or _release_version_name(release_apk_dir)
    )
    if not version_name:
        print("Skipping TV APK staging: no release version metadata was found.")
        return set()

    staged = []
    for abi in ("arm64-v8a", "armeabi-v7a"):
        candidates = sorted(release_apk_dir.glob(f"*{abi}*.apk"))
        if not candidates:
            continue
        source = candidates[0]
        dest = dist_tv / f"home-ktv-tv-{version_name}-{abi}.apk"
        validate_release_apk(source, version_name, abi)
        staged.append((source, dest))

    approved = set()
    for source, dest in staged:
        shutil.copy2(source, dest)
        print(f"Copied {source.name} -> {dest}")
        approved.add(dest.resolve())
    return approved


def should_include_file(file_path: Path, approved_files: set[Path] | None = None) -> bool:
    rel = file_path.relative_to(ROOT)
    rel_parts = rel.parts

    # APKs are admitted only when this invocation validated and staged them.
    # This prevents stale local/debug APKs from entering a later archive.
    if file_path.suffix.lower() == ".apk":
        approved = {path.resolve() for path in (approved_files or set())}
        return file_path.resolve() in approved

    # If file is directly in root directory, only allow official project files
    if len(rel_parts) == 1:
        return rel_parts[0] in ALLOWED_ROOT_FILES

    # Exclude root zip and external benchmark APK
    if file_path.name in EXCLUDE_FILES:
        return False

    # Exclude by file extension
    if file_path.suffix in EXCLUDE_EXTENSIONS:
        return False

    # Check directory exclusion
    for part in rel_parts[:-1]:
        if part in EXCLUDE_DIRS:
            return False
        # Special check for build dirs: e.g. app/build or android-tv/build
        if part == "build":
            return False

    return True


def collect_files(approved_files: set[Path] | None = None):
    included = []
    for path in sorted(ROOT.rglob("*")):
        if not path.is_file():
            continue
        if should_include_file(path, approved_files):
            included.append(path)
    return included


def create_archive(zip_name: str = "home-ktv.zip"):
    approved_apks = prepare_dist_apks()
    files = collect_files(approved_apks)
    zip_path = ROOT / zip_name

    total_uncompressed = sum(f.stat().st_size for f in files)
    print(f"Collecting {len(files)} files (uncompressed: {total_uncompressed / 1024 / 1024:.2f} MB)...")

    # Group by top directory
    by_category = {}
    for f in files:
        rel = f.relative_to(ROOT).as_posix()
        top = rel.split("/")[0] if "/" in rel else "root"
        by_category.setdefault(top, []).append(f)

    for cat, flist in sorted(by_category.items()):
        cat_size = sum(f.stat().st_size for f in flist)
        print(f"  - {cat:20} : {len(flist):4} files ({cat_size / 1024 / 1024:6.2f} MB)")

    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for f in files:
            arcname = f"home-ktv/{f.relative_to(ROOT).as_posix()}"
            zf.write(f, arcname=arcname)

    zip_size = zip_path.stat().st_size
    print(f"\nSuccessfully created archive: {zip_path.name}")
    print(f"Archive file size: {zip_size / 1024 / 1024:.2f} MB ({zip_size} bytes)")
    print(f"Compressed {len(files)} files with root prefix 'home-ktv/'")


if __name__ == "__main__":
    create_archive()
