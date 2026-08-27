"""Checks that repository POSIX entrypoints stay portable after checkout."""

from pathlib import Path


POSIX_ENTRYPOINTS = (
    Path("backend/mvnw"),
    Path("android-tv/gradlew"),
)


def main() -> int:
    failures: list[str] = []
    for path in POSIX_ENTRYPOINTS:
        if not path.is_file():
            failures.append(f"missing POSIX entrypoint: {path}")
            continue
        data = path.read_bytes()
        if b"\r" in data:
            failures.append(f"POSIX entrypoint contains CR characters: {path}")
        if not data.startswith(b"#!"):
            failures.append(f"POSIX entrypoint is missing a shebang: {path}")

    if failures:
        for failure in failures:
            print(failure)
        return 1

    print(f"checked {len(POSIX_ENTRYPOINTS)} POSIX entrypoints")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
