#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_DIR="${1:-$(pwd)}"
FILE="$PROJECT_DIR/shared/build.gradle.kts"

die() {
    echo "FOUT: $*" >&2
    exit 1
}

echo "=== Activiteitenweger Gradle fix v0.1.1 ==="

[[ -f "$FILE" ]] || die "Niet gevonden: $FILE"

BACKUP="${FILE}.bak-$(date +%Y%m%d-%H%M%S)"
cp "$FILE" "$BACKUP"
echo "Backup: $BACKUP"

python3 - "$FILE" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()

old = """        compilerOptions.configure {
            jvmTarget.set(JvmTarget.JVM_17)
        }
"""

new = """        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
"""

if old not in text:
    if new in text:
        print("Fix stond al in shared/build.gradle.kts")
        raise SystemExit(0)
    raise SystemExit(
        "Verwachte compilerOptions.configure-block niet gevonden; niets aangepast."
    )

path.write_text(text.replace(old, new, 1))
print("shared/build.gradle.kts aangepast.")
PY

echo
echo "Relevante regels:"
grep -n -A3 -B2 'compilerOptions' "$FILE"

echo
echo "Gradle build opnieuw uitvoeren..."
cd "$PROJECT_DIR"
./verify-project.sh

echo
echo "=== KLAAR ==="
