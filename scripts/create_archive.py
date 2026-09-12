"""Packages official project functional files into a clean distribution zip archive.

Excludes caches, temporary build artifacts, and non-essential local files.
"""

from pathlib import Path
import shutil
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


def prepare_dist_apks():
    """Copy built Android APKs to dist/tv-apk/ for immediate distribution."""
    dist_tv = ROOT / "dist" / "tv-apk"
    dist_tv.mkdir(parents=True, exist_ok=True)
    debug_apk_dir = ROOT / "android-tv" / "app" / "build" / "outputs" / "apk" / "debug"
    arm64 = debug_apk_dir / "app-arm64-v8a-debug.apk"
    armv7 = debug_apk_dir / "app-armeabi-v7a-debug.apk"

    if arm64.is_file():
        dest = dist_tv / "home-ktv-tv-0.1.0-arm64-v8a.apk"
        shutil.copy2(arm64, dest)
        print(f"Copied {arm64.name} -> {dest.relative_to(ROOT)}")
    if armv7.is_file():
        dest = dist_tv / "home-ktv-tv-0.1.0-armeabi-v7a.apk"
        shutil.copy2(armv7, dest)
        print(f"Copied {armv7.name} -> {dest.relative_to(ROOT)}")


def should_include_file(file_path: Path) -> bool:
    rel = file_path.relative_to(ROOT)
    rel_parts = rel.parts

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


def collect_files():
    included = []
    for path in sorted(ROOT.rglob("*")):
        if not path.is_file():
            continue
        if should_include_file(path):
            included.append(path)
    return included


def create_archive(zip_name: str = "home-ktv.zip"):
    prepare_dist_apks()
    files = collect_files()
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
