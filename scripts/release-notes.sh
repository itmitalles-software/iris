#!/usr/bin/env bash
# Print the CHANGELOG.md section of a release; it becomes the GitHub release
# body, which the in-app updater shows as the update notes.
# Usage: scripts/release-notes.sh <version> [changelog]   (version without "v", e.g. 0.2.0)
# A pre-release (0.2.0-rc.1) uses its own section when present, otherwise the
# section of its base version (0.2.0). Exits 1 when no non-empty section exists.
set -euo pipefail

VERSION="${1:?usage: release-notes.sh <version> [changelog]}"
CHANGELOG="${2:-$(cd "$(dirname "$0")/.." && pwd)/CHANGELOG.md}"

section() {
    # Heading forms: "## 0.2.0" or "## 0.2.0 — 2026-10-14". Blank lines around
    # the section are trimmed; the next "## " heading ends it.
    awk -v version="$1" '
        /^## / { if (found) exit; if ($2 == version) { found = 1; next } }
        found { lines[++n] = $0 }
        END {
            start = 1
            while (start <= n && lines[start] ~ /^[[:space:]]*$/) start++
            while (n >= start && lines[n] ~ /^[[:space:]]*$/) n--
            for (i = start; i <= n; i++) print lines[i]
        }
    ' "$CHANGELOG"
}

NOTES="$(section "$VERSION")"
if [[ -z "$NOTES" && "$VERSION" == *-* ]]; then
    NOTES="$(section "${VERSION%%-*}")"
fi
if [[ -z "$NOTES" ]]; then
    echo "error: $CHANGELOG has no '## $VERSION' section (add one before tagging)" >&2
    exit 1
fi
printf '%s\n' "$NOTES"
