#!/usr/bin/env bash
# Rewrites the Gradle lock files and gradle/verification-metadata.xml after a dependency change
# (docs/operations/supply-chain.md#pinning). Needs the code-generation database (ETRM_CODEGEN_DB_URL, ETRM_DB_USER,
# ETRM_DB_PASSWORD): assembling generates the jOOQ sources. Extra arguments go to Gradle.
set -euo pipefail
cd "$(dirname "$0")/../.."

# The components are written afresh, so versions a change replaced leave the file; the configuration block
# (trusted IDE artifacts) stays.
perl -0pi -e 's|<components>.*</components>|<components>\n   </components>|s' gradle/verification-metadata.xml

# Every configuration, and the tasks that resolve their own at execution time (compilers, detekt, ktlint, the
# SBOM), so their artifacts are recorded too. The web UI has no Gradle dependencies; npm locks its own.
# --refresh-dependencies makes Gradle read every POM again: metadata it serves from its own cache is otherwise
# never recorded, and a build with another cache (CI) then fails on it.
./gradlew -Petrm.skipWeb --refresh-dependencies --write-verification-metadata sha256 --write-locks "$@" \
  resolveAndLockAll :build-logic:resolveAndLockAll \
  assemble -x :apps:web:assemble detekt ktlintCheck :apps:api:cyclonedxDirectBom
