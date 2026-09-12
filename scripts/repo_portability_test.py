"""Checks that repository POSIX entrypoints stay portable after checkout."""

from pathlib import Path
import re


POSIX_ENTRYPOINTS = (
    Path("backend/mvnw"),
    Path("android-tv/gradlew"),
)

WRAPPER_CONFIGS = (
    Path("backend/.mvn/wrapper/maven-wrapper.properties"),
    Path("android-tv/gradle/wrapper/gradle-wrapper.properties"),
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

    for path in WRAPPER_CONFIGS:
        if not path.is_file():
            failures.append(f"missing wrapper config: {path}")
            continue
        text = path.read_text(encoding="utf-8")
        match = re.search(r"^distributionUrl=(.+)$", text, re.MULTILINE)
        if not match:
            failures.append(f"wrapper config has no distributionUrl: {path}")
            continue
        url = match.group(1).replace(r"\:", ":")
        if "mirrors.cloud.tencent.com" in url or "maven.aliyun.com" in url:
            failures.append(f"wrapper uses a regional mirror: {path}")
        if path.name == "gradle-wrapper.properties" and not url.startswith("https://services.gradle.org/"):
            failures.append(f"Gradle wrapper must use the official distribution host: {path}")
        if path.name == "maven-wrapper.properties" and not url.startswith("https://repo.maven.apache.org/"):
            failures.append(f"Maven wrapper must use the official distribution host: {path}")

    if failures:
        for failure in failures:
            print(failure)
        return 1

    print(f"checked {len(POSIX_ENTRYPOINTS)} POSIX entrypoints and {len(WRAPPER_CONFIGS)} wrapper configs")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
