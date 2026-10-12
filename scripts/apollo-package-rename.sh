#!/usr/bin/env bash
# Rewrites the upstream Hermes Mobile package (com.m57.hermescontrol) to
# Apollo's package (io.wutz.apollo).
#
# Idempotent: safe to run on an already-renamed tree. Run it after merging
# upstream changes (see UPSTREAM.md) so files added upstream under the old
# package path are moved and their package/import lines rewritten.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

OLD_PKG="com.m57.hermescontrol"
NEW_PKG="io.wutz.apollo"
OLD_PATH="com/m57/hermescontrol"
NEW_PATH="io/wutz/apollo"

# 1. Move source sets (main, test, androidTest, debug, ...).
for src in app/src/*/java app/src/*/kotlin; do
    [ -d "$src/$OLD_PATH" ] || continue
    mkdir -p "$src/$NEW_PATH"
    # Merge file by file so upstream additions land next to existing files.
    while IFS= read -r -d '' file; do
        rel="${file#"$src/$OLD_PATH/"}"
        mkdir -p "$(dirname "$src/$NEW_PATH/$rel")"
        git mv -f "$file" "$src/$NEW_PATH/$rel" 2>/dev/null || mv -f "$file" "$src/$NEW_PATH/$rel"
    done < <(find "$src/$OLD_PATH" -type f -print0)
    find "$src/com" -depth -type d -empty -delete 2>/dev/null || true
done

# 2. Move Room schema directories (named after the database class).
for dir in app/schemas/"$OLD_PKG".*; do
    [ -d "$dir" ] || continue
    target="app/schemas/${NEW_PKG}${dir#app/schemas/$OLD_PKG}"
    mkdir -p "$target"
    for file in "$dir"/*; do
        git mv -f "$file" "$target/" 2>/dev/null || mv -f "$file" "$target/"
    done
    rmdir "$dir" 2>/dev/null || true
done

# 3. Rewrite references in tracked text files. Historical docs that describe
# the upstream project (NOTICE, UPSTREAM.md) keep the original identifier.
git ls-files -z -- \
    'app/**' '*.kts' '*.pro' '*.toml' '.github/**' 'scripts/**' 'flake.nix' \
    ':!:NOTICE' ':!:UPSTREAM.md' ':!:scripts/apollo-package-rename.sh' |
    xargs -0 grep -lIZ -e "$OLD_PKG" -e "$OLD_PATH" 2>/dev/null |
    xargs -0 -r sed -i -e "s#${OLD_PKG//./\\.}#${NEW_PKG}#g" -e "s#${OLD_PATH}#${NEW_PATH}#g"

echo "Package rename complete: $OLD_PKG -> $NEW_PKG"
