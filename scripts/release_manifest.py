"""Create and verify a small, reproducible release evidence manifest.

The manifest is deliberately independent of GitHub APIs.  It binds the source
revision and database migration level to the exact files uploaded to a release;
the workflow supplies the immutable GHCR digest when one is available.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Iterable


SAFE_VERSION = re.compile(
    r"^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?$"
)
MIGRATION = re.compile(r"^V([0-9]+)__[^/\\]+\.sql$")
SCHEMA_VERSION = 1


def latest_migration(repository: Path) -> int:
    """Return the highest numeric Flyway migration in the repository."""

    migration_dir = repository / "backend" / "src" / "main" / "resources" / "db" / "migration"
    versions = []
    for path in migration_dir.glob("V*__*.sql"):
        match = MIGRATION.match(path.name)
        if match:
            versions.append(int(match.group(1)))
    return max(versions, default=0)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _relative_files(artifact_dir: Path, includes: Iterable[str] | None, output: Path) -> list[Path]:
    root = artifact_dir.resolve()
    output_resolved = output.resolve()
    if includes:
        candidates = []
        for value in includes:
            candidate = (artifact_dir / value).resolve()
            if root not in candidate.parents or not candidate.is_file():
                raise ValueError(f"artifact is not a file below artifact directory: {value}")
            candidates.append(candidate)
    else:
        candidates = [path for path in root.rglob("*") if path.is_file()]
    return sorted(
        (path for path in candidates if path.resolve() != output_resolved),
        key=lambda path: path.relative_to(root).as_posix(),
    )


def build_manifest(
    repository: Path,
    output: Path,
    release_tag: str,
    version: str,
    channel: str,
    source_sha: str,
    build_time: str,
    artifact_dir: Path,
    includes: Iterable[str] | None = None,
    image_ref: str | None = None,
) -> dict:
    if not release_tag.strip():
        raise ValueError("release tag must not be empty")
    if not SAFE_VERSION.fullmatch(version):
        raise ValueError(f"invalid release version: {version}")
    if channel not in {"stable", "unstable"}:
        raise ValueError(f"invalid release channel: {channel}")
    if not source_sha.strip():
        raise ValueError("source SHA must not be empty")

    artifacts = []
    root = artifact_dir.resolve()
    for path in _relative_files(artifact_dir, includes, output):
        relative = path.relative_to(root).as_posix()
        artifacts.append({
            "path": relative,
            "size": path.stat().st_size,
            "sha256": sha256(path),
        })

    manifest = {
        "schemaVersion": SCHEMA_VERSION,
        "releaseTag": release_tag,
        "version": version,
        "channel": channel,
        "source": {
            "gitSha": source_sha,
            "latestMigration": latest_migration(repository.resolve()),
            "buildTime": build_time,
        },
        "artifacts": artifacts,
    }
    if image_ref:
        manifest["image"] = {"ref": image_ref}
    return manifest


def write_manifest(manifest: dict, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def verify_manifest(
    repository: Path,
    manifest_path: Path,
    *,
    expected_release_tag: str | None = None,
    expected_version: str | None = None,
    expected_channel: str | None = None,
    expected_source_sha: str | None = None,
    expected_image_ref: str | None = None,
) -> list[str]:
    errors: list[str] = []
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        return [f"cannot read manifest: {error}"]

    if not isinstance(manifest, dict):
        return ["manifest root must be an object"]
    if manifest.get("schemaVersion") != SCHEMA_VERSION:
        errors.append("unsupported manifest schemaVersion")

    release_tag = manifest.get("releaseTag")
    if not isinstance(release_tag, str) or not release_tag.strip():
        errors.append("manifest releaseTag is missing or empty")
    elif expected_release_tag is not None and release_tag != expected_release_tag:
        errors.append(
            f"releaseTag mismatch: manifest={release_tag} expected={expected_release_tag}"
        )

    version = manifest.get("version")
    if not isinstance(version, str) or not SAFE_VERSION.fullmatch(version):
        errors.append("manifest version is missing or invalid")
    elif expected_version is not None and version != expected_version:
        errors.append(f"version mismatch: manifest={version} expected={expected_version}")

    channel = manifest.get("channel")
    if channel not in {"stable", "unstable"}:
        errors.append("manifest channel is missing or invalid")
    elif expected_channel is not None and channel != expected_channel:
        errors.append(f"channel mismatch: manifest={channel} expected={expected_channel}")

    source = manifest.get("source") or {}
    if not isinstance(source, dict):
        errors.append("manifest source must be an object")
        source = {}
    source_sha = source.get("gitSha")
    if not isinstance(source_sha, str) or not source_sha.strip():
        errors.append("manifest source.gitSha is missing or empty")
    elif expected_source_sha is not None and source_sha != expected_source_sha:
        errors.append(
            f"source SHA mismatch: manifest={source_sha} expected={expected_source_sha}"
        )

    image = manifest.get("image") or {}
    if not isinstance(image, dict):
        errors.append("manifest image must be an object")
        image = {}
    image_ref = image.get("ref")
    if expected_image_ref is not None and image_ref != expected_image_ref:
        errors.append(f"image ref mismatch: manifest={image_ref} expected={expected_image_ref}")

    expected_migration = latest_migration(repository.resolve())
    if source.get("latestMigration") != expected_migration:
        errors.append(
            f"latest migration mismatch: manifest={source.get('latestMigration')} current={expected_migration}"
        )

    manifest_root = manifest_path.resolve().parent
    artifact_items = manifest.get("artifacts")
    if not isinstance(artifact_items, list) or not artifact_items:
        errors.append("manifest contains no artifacts")
        return errors
    for item in artifact_items:
        if not isinstance(item, dict) or not isinstance(item.get("path"), str):
            errors.append("manifest contains an invalid artifact entry")
            continue
        path = (manifest_root / item["path"]).resolve()
        if manifest_root not in path.parents or not path.is_file():
            errors.append(f"artifact is missing: {item['path']}")
            continue
        actual_hash = sha256(path)
        actual_size = path.stat().st_size
        if item.get("sha256") != actual_hash:
            errors.append(f"artifact hash mismatch: {item['path']}")
        if item.get("size") != actual_size:
            errors.append(f"artifact size mismatch: {item['path']}")
    return errors


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    build = subparsers.add_parser("build")
    build.add_argument("--repository", type=Path, required=True)
    build.add_argument("--output", type=Path, required=True)
    build.add_argument("--release-tag", required=True)
    build.add_argument("--version", required=True)
    build.add_argument("--channel", required=True)
    build.add_argument("--source-sha", required=True)
    build.add_argument("--build-time", default="")
    build.add_argument("--image-ref")
    build.add_argument("--artifact-dir", type=Path, required=True)
    build.add_argument("--include", action="append")

    verify = subparsers.add_parser("verify")
    verify.add_argument("--repository", type=Path, required=True)
    verify.add_argument("--manifest", type=Path, required=True)
    verify.add_argument("--release-tag")
    verify.add_argument("--version")
    verify.add_argument("--channel")
    verify.add_argument("--source-sha")
    verify.add_argument("--image-ref")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    try:
        if args.command == "build":
            manifest = build_manifest(
                repository=args.repository,
                output=args.output,
                release_tag=args.release_tag,
                version=args.version,
                channel=args.channel,
                source_sha=args.source_sha,
                build_time=args.build_time,
                artifact_dir=args.artifact_dir,
                includes=args.include,
                image_ref=args.image_ref,
            )
            write_manifest(manifest, args.output)
            errors = verify_manifest(
                args.repository,
                args.output,
                expected_release_tag=args.release_tag,
                expected_version=args.version,
                expected_channel=args.channel,
                expected_source_sha=args.source_sha,
                expected_image_ref=args.image_ref,
            )
            if errors:
                for error in errors:
                    print(error, file=sys.stderr)
                return 1
            return 0
        errors = verify_manifest(
            args.repository,
            args.manifest,
            expected_release_tag=args.release_tag,
            expected_version=args.version,
            expected_channel=args.channel,
            expected_source_sha=args.source_sha,
            expected_image_ref=args.image_ref,
        )
        if errors:
            for error in errors:
                print(error, file=sys.stderr)
            return 1
        return 0
    except (OSError, ValueError) as error:
        print(str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
