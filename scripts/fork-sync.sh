#!/usr/bin/env bash
# Rebase the fork branch onto an upstream release tag and run the fork's tests.
# Usage: scripts/fork-sync.sh <upstream-tag> [miuix-checkout]
set -euo pipefail

tag="${1:?usage: scripts/fork-sync.sh <upstream-tag> [miuix-checkout]}"
miuix="${2:-${BILI_MIUIX_SOURCE:-}}"
branch="bilipai-plus"

cd "$(git rev-parse --show-toplevel)"
if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
  echo "Working tree has uncommitted changes; commit or stash them first." >&2
  exit 1
fi

git fetch upstream --tags
git checkout "$branch"
base="$(git merge-base "$branch" "refs/tags/$tag" 2>/dev/null || true)"
if [[ "$base" == "$(git rev-parse "refs/tags/$tag^{commit}")" ]]; then
  echo "$branch already contains $tag."
else
  backup="$branch-before-$tag"
  git branch -f "$backup" "$branch"
  echo "Saved the current branch as $backup."
  # Rebase only the fork's own commits: those after the previous upstream base.
  previous="$(git describe --tags --abbrev=0 --match 'v*' "$branch")"
  if ! git rebase --onto "refs/tags/$tag" "refs/tags/$previous" "$branch"; then
    echo "Rebase stopped on a conflict. Resolve it (see FORK.md, Hooks), then: git rebase --continue" >&2
    exit 2
  fi
fi

gradle_args=(:btr-transport:testDebugUnitTest :app:compileDebugKotlin)
if [[ -n "$miuix" ]]; then gradle_args=("-Pbili.miuix.source=$miuix" "${gradle_args[@]}"); fi
./gradlew "${gradle_args[@]}"
echo "$branch is on $tag and the fork's tests pass."
