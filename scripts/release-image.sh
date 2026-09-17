#!/usr/bin/env bash
#
# Build and push the OCI image for a version semantic-release has just computed.
#
# Called from release.config.mjs as a `prepare` step, which is deliberate: prepare runs BEFORE the
# git tag is written, so a failure here aborts the release rather than leaving a tag that names an
# image nobody can pull.
#
# Buildpacks, not the Dockerfile — no base image to patch, and a CVE fix is a rebuild rather than an
# edit. The Dockerfile in this repo is the fallback for environments that require one.
set -euo pipefail

VERSION="${1:?usage: release-image.sh <version>}"

# Named after the organisation this workflow is running in, never a string in the POM: the org has
# been renamed once already and GHCR does not follow that redirect for packages.
OWNER="${IMAGE_OWNER:?IMAGE_OWNER must be set (github.repository_owner)}"
IMAGE="ghcr.io/${OWNER}/auth-service"

echo "Building ${IMAGE}:${VERSION}"
./mvnw -B --no-transfer-progress \
  -Drevision="${VERSION}" \
  -Dimage.owner="${OWNER}" \
  -DskipTests \
  spring-boot:build-image

# One tag, and it is immutable. ADR-010 §4: a GitOps manifest must never reference a mutable tag,
# and the simplest way to keep that true is not to publish one. A human who wants the newest build
# reads the release page.
echo "Pushing ${IMAGE}:${VERSION}"
docker push "${IMAGE}:${VERSION}"

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### Image"
    echo '```'
    echo "${IMAGE}:${VERSION}"
    docker image inspect "${IMAGE}:${VERSION}" --format 'platform: {{.Os}}/{{.Architecture}}'
    echo '```'
  } >> "${GITHUB_STEP_SUMMARY}"
fi
