#!/usr/bin/env bash
set -euo pipefail

body_file="conflict-pr.md"

{
  echo "@${NOTIFY_USER} automatic upstream sync could not merge cleanly."
  echo
  echo "This PR carries the upstream branch into ${TARGET_BRANCH}. Resolve the conflicts here, then merge this PR to sync the fork."
  echo
  echo "- Target branch: ${TARGET_BRANCH}"
  echo "- Upstream: ${UPSTREAM_REPO}@${UPSTREAM_BRANCH}"
  echo "- Upstream commit: ${UPSTREAM_SHA}"
  echo "- Workflow run: ${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}"
  echo
  echo "Conflicting files:"
  echo '```'
  cat conflict-files.txt
  echo '```'
  echo
  echo "Merge output:"
  echo '```'
  sed -n '1,160p' merge-output.log
  echo '```'
} > "${body_file}"

safe_name() {
  printf '%s' "$1" | tr -c 'A-Za-z0-9._-' '-'
}

safe_target="$(safe_name "${TARGET_BRANCH}")"
safe_upstream="$(safe_name "${UPSTREAM_BRANCH}")"
notice_branch="automation/upstream-sync-${safe_target}-${safe_upstream}"

git fetch origin "${TARGET_BRANCH}"
git fetch upstream "${UPSTREAM_BRANCH}"
git checkout -B "${notice_branch}" "upstream/${UPSTREAM_BRANCH}"
git push --force origin "HEAD:${notice_branch}"

pr="$(
  gh pr list \
    --repo "${GITHUB_REPOSITORY}" \
    --state open \
    --head "${notice_branch}" \
    --base "${TARGET_BRANCH}" \
    --limit 1 \
    --json number \
    --jq '.[0].number'
)"

if [ -n "${pr}" ] && [ "${pr}" != "null" ]
then
  gh pr edit "${pr}" \
    --repo "${GITHUB_REPOSITORY}" \
    --title "${TITLE}" \
    --body-file "${body_file}"
else
  gh pr create \
    --repo "${GITHUB_REPOSITORY}" \
    --head "${notice_branch}" \
    --base "${TARGET_BRANCH}" \
    --title "${TITLE}" \
    --body-file "${body_file}"
  pr="$(
    gh pr list \
      --repo "${GITHUB_REPOSITORY}" \
      --state open \
      --head "${notice_branch}" \
      --base "${TARGET_BRANCH}" \
      --limit 1 \
      --json number \
      --jq '.[0].number'
  )"
fi

if [ -n "${pr}" ] && [ "${pr}" != "null" ]
then
  gh pr edit "${pr}" \
    --repo "${GITHUB_REPOSITORY}" \
    --add-assignee "${NOTIFY_USER}" || true
fi
