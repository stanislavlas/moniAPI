# AGENTS.md

## Version bumping

This repo is a Home Assistant add-on. HA detects updates by comparing the `version` field in `config.yaml`.

**Before every commit:**
1. Determine the appropriate version bump based on the change:
   - `PATCH` (x.x.1) — bug fixes, config tweaks, documentation
   - `MAJOR` (2.0.0) — breaking changes
   - `MINOR` (x.1.0) — new features, non-breaking changes
2. Propose the new version to the user: "I'll bump the version from `1.0.0` to `1.0.1` (patch — reason). OK?"
3. Wait for confirmation or override before committing.
4. Include the `config.yaml` version bump in the same commit.
