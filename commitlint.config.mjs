/**
 * The commit log is the version number, so a message that parses wrongly is a release that does not
 * happen — or worse, a MAJOR that should have been a PATCH. This fails the pull request instead.
 *
 * `feature`, `patch` and `breaking` are accepted alongside the standard spellings because this
 * project already writes them and `scripts/version.sh` already accepted them. Dropping them would
 * quietly reclassify existing history as "no release".
 */
export default {
  extends: ["@commitlint/config-conventional"],
  rules: {
    "type-enum": [
      2,
      "always",
      [
        "feat", "feature",     // → MINOR
        "fix", "patch",        // → PATCH
        "breaking",            // → MAJOR (as does a `!` suffix or a BREAKING CHANGE footer)
        "perf", "refactor",    // → PATCH
        "deps",                // → PATCH, for a dependency bump
        "build", "chore", "ci", "docs", "style", "test", "revert",
      ],
    ],
    // Long enough for a real sentence, short enough to read in `git log --oneline`.
    "header-max-length": [2, "always", 100],
    // The body explains why; the subject says what. Neither should be shouting.
    "subject-case": [2, "never", ["start-case", "pascal-case", "upper-case"]],
  },
};
