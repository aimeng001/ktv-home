import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JAVA_TRIVIA = re.compile(
    r'//[^\r\n]*|/\*[\s\S]*?\*/|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\''
)


def _java_code_without_comments_or_literals(source: str) -> str:
    def preserve_lines(match: re.Match[str]) -> str:
        return "".join("\n" if char == "\n" else " " for char in match.group(0))

    return JAVA_TRIVIA.sub(preserve_lines, source)


class TestcontainersIsolationTest(unittest.TestCase):
    def test_every_testcontainers_class_is_isolated_from_parallel_classes(self):
        test_root = ROOT / "backend" / "src" / "test" / "java"
        discovered = []
        missing_isolation = []

        for source in test_root.rglob("*.java"):
            code = _java_code_without_comments_or_literals(
                source.read_text(encoding="utf-8")
            )
            for annotation in re.finditer(r"@Testcontainers\b", code):
                declaration = code[annotation.end() :]
                class_match = re.search(r"\bclass\s+([A-Za-z_$][\w$]*)", declaration)
                self.assertIsNotNone(class_match, str(source))
                class_name = class_match.group(1)
                discovered.append(class_name)
                class_annotations = declaration[: class_match.start()]
                if not re.search(r"@Isolated\b", class_annotations):
                    missing_isolation.append(f"{class_name} ({source.relative_to(ROOT)})")

        self.assertTrue(discovered, "No @Testcontainers test classes were discovered")
        self.assertEqual(
            [],
            missing_isolation,
            "Testcontainers JUnit extension classes must be isolated from parallel "
            "test classes:\n" + "\n".join(missing_isolation),
        )


if __name__ == "__main__":
    unittest.main()
