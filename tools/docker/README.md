# Empty Docker config for Bazel's image fetches

`rules_oci` reads `~/.docker/config.json` when fetching a base image, and calls
the configured `credsStore` helper for *every* registry — including an
anonymous pull of a public image. A machine that once had Docker Desktop keeps
`"credsStore": "desktop"` in that file, so the helper is still on PATH but
returns `credentials not found in native keychain`, which rules_oci treats as
fatal. The image build then fails on a public base image that needs no
credentials at all.

Pointing `DOCKER_CONFIG` at this directory for repository fetches gives
rules_oci a config with no credential helper, so it pulls anonymously. It
affects Bazel only — the real `~/.docker/config.json` is untouched, and
`docker` on the command line still uses it.

If a base image ever does need authentication, drop the `--repo_env` line from
`.bazelrc` and fix the host config instead.
