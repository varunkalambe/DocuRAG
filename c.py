from pathlib import Path


# ============================================================
# CONFIGURATION
# ============================================================

# The project root is the directory from which this script runs.
PROJECT_ROOT = Path.cwd().resolve()

# Output file
OUTPUT_FILE = PROJECT_ROOT / "project_code_dump.txt"


# ------------------------------------------------------------
# SOURCE CODE EXTENSIONS
# ------------------------------------------------------------
# Only these file types will be included.
# Add/remove extensions here whenever your project grows.
# ------------------------------------------------------------

CODE_EXTENSIONS = {
    # Python / Backend
    ".py",

    # JavaScript / TypeScript
    ".js",
    ".jsx",
    ".ts",
    ".tsx",
    ".mjs",
    ".cjs",

    # Angular / Web
    ".html",
    ".css",
    ".scss",
    ".sass",
    ".less",

    # Other common source files
    ".java",
    ".c",
    ".cpp",
    ".h",
    ".hpp",
    ".cs",
    ".go",
    ".rs",
    ".php",
    ".rb",

    # Shell / scripts
    ".sh",
    ".bat",
    ".cmd",
    ".ps1",
}


# ------------------------------------------------------------
# DIRECTORIES TO COMPLETELY IGNORE
# ------------------------------------------------------------

IGNORED_DIRECTORIES = {
    # Python
    ".venv",
    "venv",
    "env",
    ".env",
    "__pycache__",
    ".pytest_cache",
    ".mypy_cache",
    ".ruff_cache",

    # Node / Angular
    "node_modules",
    "dist",
    "build",
    ".angular",
    ".nx",
    ".cache",

    # Git / IDE
    ".git",
    ".github",
    ".idea",
    ".vscode",

    # Testing / coverage
    "coverage",
    "htmlcov",

    # General generated files
    "target",
    "bin",
    "obj",
    "out",
    "tmp",
    "temp",
}


# ------------------------------------------------------------
# FILE NAMES TO IGNORE
# ------------------------------------------------------------
# These are generally dependency/config/secret/generated files
# rather than application source code.
# ------------------------------------------------------------

IGNORED_FILES = {
    # This script's output
    "project_code_dump.txt",

    # Environment / secrets
    ".env",
    ".env.local",
    ".env.development",
    ".env.production",

    # Python dependency files
    "requirements.txt",
    "requirements-dev.txt",
    "Pipfile",
    "Pipfile.lock",
    "poetry.lock",
    "pyproject.toml",

    # Node dependency files
    "package-lock.json",
    "npm-shrinkwrap.json",
    "yarn.lock",
    "pnpm-lock.yaml",

    # Angular / build metadata
    "package.json",
    "angular.json",
    "tsconfig.json",
    "tsconfig.app.json",
    "tsconfig.spec.json",

    # Git / editor
    ".gitignore",
    ".gitattributes",
    ".editorconfig",

    # Documentation
    "README.md",
}


# ------------------------------------------------------------
# EXTRA FILE EXTENSIONS TO IGNORE
# ------------------------------------------------------------

IGNORED_EXTENSIONS = {
    # Images
    ".png",
    ".jpg",
    ".jpeg",
    ".gif",
    ".webp",
    ".svg",
    ".ico",
    ".bmp",

    # Documents
    ".pdf",
    ".doc",
    ".docx",
    ".xls",
    ".xlsx",
    ".ppt",
    ".pptx",

    # Archives
    ".zip",
    ".rar",
    ".7z",
    ".tar",
    ".gz",

    # Compiled / binary
    ".exe",
    ".dll",
    ".so",
    ".dylib",
    ".pyc",
    ".pyo",
    ".class",

    # Database / generated
    ".db",
    ".sqlite",
    ".sqlite3",

    # Logs
    ".log",
}


# ============================================================
# HELPER FUNCTIONS
# ============================================================

def should_ignore_directory(directory: Path) -> bool:
    """
    Returns True if the directory should not be traversed.
    """
    return directory.name in IGNORED_DIRECTORIES


def should_include_file(file_path: Path) -> bool:
    """
    Returns True only for source-code files we want to dump.
    """

    # Ignore specific filenames
    if file_path.name in IGNORED_FILES:
        return False

    # Ignore files with explicitly ignored extensions
    if file_path.suffix.lower() in IGNORED_EXTENSIONS:
        return False

    # Include only known source-code extensions
    if file_path.suffix.lower() not in CODE_EXTENSIONS:
        return False

    return True


def read_file_safely(file_path: Path) -> str:
    """
    Reads a source file safely.

    Handles UTF-8 first and falls back to another encoding
    if necessary so one problematic file does not stop
    the entire dump.
    """

    try:
        return file_path.read_text(
            encoding="utf-8"
        )

    except UnicodeDecodeError:
        try:
            return file_path.read_text(
                encoding="utf-8-sig"
            )

        except UnicodeDecodeError:
            return file_path.read_text(
                encoding="cp1252",
                errors="replace"
            )


# ============================================================
# MAIN DUMP FUNCTION
# ============================================================

def create_code_dump() -> None:

    print("=" * 70)
    print("PROJECT CODE DUMP")
    print("=" * 70)

    print(f"Project root : {PROJECT_ROOT}")
    print(f"Output file  : {OUTPUT_FILE}")
    print()

    included_files = []
    skipped_files = []

    # --------------------------------------------------------
    # Recursively walk through every directory
    # --------------------------------------------------------

    for current_directory, directories, files in __import__("os").walk(PROJECT_ROOT):

        current_path = Path(current_directory)

        # Remove ignored directories from traversal.
        # This prevents entering .venv, node_modules, etc.
        directories[:] = [
            directory
            for directory in directories
            if directory not in IGNORED_DIRECTORIES
        ]

        # Process every file
        for filename in files:

            file_path = current_path / filename

            # Never include the output file itself
            if file_path.resolve() == OUTPUT_FILE.resolve():
                continue

            if should_include_file(file_path):
                included_files.append(file_path)

            else:
                skipped_files.append(file_path)

    # Sort files so the output is deterministic
    included_files.sort(
        key=lambda path: str(path).lower()
    )

    # --------------------------------------------------------
    # Write complete dump
    # --------------------------------------------------------

    with OUTPUT_FILE.open(
        "w",
        encoding="utf-8",
        newline="\n"
    ) as output:

        output.write(
            "============================================================\n"
        )
        output.write("PDF RAG APPLICATION - COMPLETE SOURCE CODE DUMP\n")
        output.write(
            "============================================================\n\n"
        )

        output.write(
            f"PROJECT ROOT:\n{PROJECT_ROOT}\n\n"
        )

        output.write(
            f"TOTAL SOURCE FILES: {len(included_files)}\n\n"
        )

        output.write(
            "This file contains source-code files only.\n"
            "Generated files, virtual environments, node_modules,\n"
            "dependencies, secrets, binaries, documentation,\n"
            "images, PDFs and build/cache directories are excluded.\n\n"
        )

        output.write(
            "============================================================\n"
            "START OF SOURCE CODE\n"
            "============================================================\n\n"
        )

        # ----------------------------------------------------
        # Write every source file
        # ----------------------------------------------------

        for index, file_path in enumerate(
            included_files,
            start=1
        ):

            absolute_path = file_path.resolve()

            output.write(
                "\n"
                + "#" * 80
                + "\n"
            )

            output.write(
                f"FILE {index}\n"
            )

            output.write(
                f"FULL PATH:\n{absolute_path}\n"
            )

            output.write(
                f"RELATIVE PATH:\n"
                f"{file_path.relative_to(PROJECT_ROOT)}\n"
            )

            output.write(
                "#" * 80
                + "\n\n"
            )

            try:
                code = read_file_safely(file_path)

                output.write(code)

                # Guarantee separation between files
                if not code.endswith("\n"):
                    output.write("\n")

            except Exception as error:

                output.write(
                    f"\n[ERROR READING FILE: {error}]\n"
                )

            output.write(
                "\n"
                + "#" * 80
                + "\n"
                + f"END OF FILE: {absolute_path}\n"
                + "#" * 80
                + "\n\n"
            )

        output.write(
            "============================================================\n"
            "END OF SOURCE CODE\n"
            "============================================================\n"
        )

    # --------------------------------------------------------
    # Console summary
    # --------------------------------------------------------

    print("DONE")
    print()

    print(
        f"Source files included : {len(included_files)}"
    )

    print(
        f"Files skipped         : {len(skipped_files)}"
    )

    print(
        f"Output created        : {OUTPUT_FILE}"
    )

    print()
    print("Included source files:")

    for file_path in included_files:
        print(
            f"  {file_path.resolve()}"
        )

    print()
    print("=" * 70)


# ============================================================
# ENTRY POINT
# ============================================================

if __name__ == "__main__":
    create_code_dump()