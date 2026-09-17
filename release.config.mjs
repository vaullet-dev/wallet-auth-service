/**
 * semantic-release — the one place that answers "what version is this build?".
 *
 * No version number is committed anywhere in this repository and `main` never carries a
 * `-SNAPSHOT`. Both follow from the same decision: a version is a statement about what changed, and
 * the only record of what changed is the commit log. Deriving the number from the log means it
 * cannot disagree with the code, and there is no "bump the version" commit to forget or to conflict.
 *
 * This replaces the hand-rolled `scripts/version.sh` the ledger uses. That script answers "what
 * number is this build" correctly, but it does not create the tag, write the changelog entry, or
 * publish release notes — which is why `backend-common`'s CHANGELOG still has everything under
 * **Unreleased** while 0.1.0 and 0.1.1 sit in GHCR. The tool closes the gap between what was
 * released and what the repository says was released.
 *
 * Maven needs no change to participate: the POM's version is already `${revision}`, so the computed
 * number is passed in on the command line and `flatten-maven-plugin` substitutes the literal into
 * the installed POM.
 */
export default {
  /**
   * `master`, because that is what this repository's default branch is actually called. Listing a
   * branch that does not exist on the remote is not a warning — semantic-release refuses to run at
   * all, with ERELEASEBRANCHES. Renaming the branch on GitHub means changing it here in the same
   * commit that changes the workflow trigger.
   */
  branches: ["master"],

  /** `v1.2.3`, matching the ledger's tags so `git describe` output reads the same across repos. */
  tagFormat: "v${version}",

  plugins: [
    /**
     * Three commit types, three levels — plus the aliases this project already writes.
     * `scripts/version.sh` accepted `feature`, `patch` and `breaking` as spellings of `feat`, `fix`
     * and `!`, and the log is full of them, so the rules have to keep accepting them or history
     * silently stops producing releases.
     */
    [
      "@semantic-release/commit-analyzer",
      {
        preset: "conventionalcommits",
        releaseRules: [
          { breaking: true, release: "major" },
          { type: "breaking", release: "major" },
          { type: "feat", release: "minor" },
          { type: "feature", release: "minor" },
          { type: "fix", release: "patch" },
          { type: "patch", release: "patch" },
          { type: "perf", release: "patch" },
          { type: "refactor", release: "patch" },
          { type: "deps", release: "patch" },
          // Explicitly no release. Listed rather than left to the default so that "why did my
          // commit not ship" has an answer in this file.
          { type: "chore", release: false },
          { type: "docs", release: false },
          { type: "test", release: false },
          { type: "ci", release: false },
          { type: "style", release: false },
        ],
      },
    ],

    [
      "@semantic-release/release-notes-generator",
      {
        preset: "conventionalcommits",
        presetConfig: {
          types: [
            { type: "feat", section: "Features" },
            { type: "feature", section: "Features" },
            { type: "fix", section: "Fixes" },
            { type: "patch", section: "Fixes" },
            { type: "perf", section: "Performance" },
            { type: "refactor", section: "Internal" },
            { type: "deps", section: "Dependencies" },
            { type: "docs", section: "Documentation", hidden: true },
            { type: "chore", hidden: true },
            { type: "test", hidden: true },
            { type: "ci", hidden: true },
            { type: "style", hidden: true },
          ],
        },
      },
    ],

    ["@semantic-release/changelog", { changelogFile: "CHANGELOG.md" }],

    /**
     * Build and push the image HERE, in `prepare`, and not in `publish`.
     *
     * semantic-release writes the git tag between `prepare` and `publish`. Pushing the image from a
     * publish plugin would therefore tag a commit whose image may not exist — and the next run would
     * compute the next version from that tag, silently skipping the gap. Doing it in prepare means a
     * failed build or a failed push aborts the release before anything is tagged, which is the
     * ordering the ledger's workflow argues for in a comment and gets by hand.
     */
    [
      "@semantic-release/exec",
      { prepareCmd: "./scripts/release-image.sh ${nextRelease.version}" },
    ],

    /**
     * The changelog is committed back to `master`. `[skip ci]` stops that commit re-triggering this
     * workflow — without it every release starts another build that finds nothing to release.
     */
    [
      "@semantic-release/git",
      {
        assets: ["CHANGELOG.md"],
        message: "chore(release): ${nextRelease.version} [skip ci]\n\n${nextRelease.notes}",
      },
    ],

    /**
     * The GitHub release, last, once the image exists and the tag is written. No `successComment`:
     * this is a solo repository and a bot commenting on every issue a release touches is noise.
     */
    ["@semantic-release/github", { successComment: false, failComment: false }],
  ],
};
