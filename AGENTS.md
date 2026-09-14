# Repository Guidelines

## Agent Routing, Review, and Git Control

Use **Sol with medium reasoning effort** as the primary coordinator and default
model for core tasks. It owns scope, integration, validation, and the final
report; delegating work never transfers that accountability. Do not use Terra as
a default or required model.

Select the model and reasoning level deliberately:

- **Sol, `medium`:** the default for core tasks, including ordinary
  implementation, focused tests, coordination, validation, and final reporting.
- **Sol, `high` or Astra, `medium`:** architecture, hard bugs, migrations,
  unresolved lifecycle or persistence issues, security, Vault-contract changes,
  compatibility, and other measured high-complexity work.
- **Luna, `max`:** questions, read-only checks, small documentation edits,
  inventories, formatting, boilerplate, test-data preparation, and other simple
  or bounded mechanical work. `max` is Luna's highest available reasoning level.

Before starting an implementation task, classify its risk and explicitly state
one execution mode in the first progress update; file count alone is not a risk
signal.

- **Simple mode (default; L0 or L1):** Use one agent only. Luna at `max` handles
  L0 work. Sol at `medium` performs L1 discovery, implementation, affected
  tests, and the final report. Do not create sub-agents or request a separate
  review unless the user asks for one or the task is reclassified.
- **Multi-agent mode (L2 or L3, or explicitly requested):** State the level,
  each role, file ownership, and the planned handoffs before creating
  sub-agents. Use the workflow in the table below. Sub-agents may work in
  parallel only when their files and decisions are independent.

| Level | Scope | Required workflow |
| --- | --- | --- |
| L0 | Questions, read-only work, typo fixes, isolated documentation | Simple mode: Luna at `max` only; no sub-agent. |
| L1 | Local UI or pure-logic change without persistence, permissions, or contract effects | Simple mode: Sol at `medium` implements and runs focused tests. |
| L2 | Multi-screen or substantial user-visible feature without Vault/sync/concurrency risk | Multi-agent mode: Sol at `high` or Astra at `medium` provides a short read-only design review; Sol at `medium` implements and validates; the original reviewer performs final review. |
| L3 | Vault data, deletes, permissions, sync, concurrency, migrations, cross-client contracts, or security | Multi-agent mode: Sol at `high` or Astra at `medium` reviewer → Sol at `medium` developer → independent Sol at `medium` tester → original reviewer. |

For L2 and L3, the developer updates design, contract, spec, plan, and task
documents before implementation when the change affects them. Reviewer findings
must cite concrete files and acceptance criteria. The independent L3 tester does not
modify production code, distinguishes automated from real-device evidence, and
reruns affected checks after repairs.

Use a compact handoff card for every sub-agent: objective, in-scope and
out-of-scope boundaries, relevant files, non-negotiable constraints, and exact
acceptance commands. Do not pass full conversation history when that card and
the named files are sufficient. Do not let agents edit the same files
concurrently; preserve user changes, give each editing phase explicit file
ownership, and keep task records singular.

The developer runs fast, affected checks during implementation. The independent
tester runs the final full suite once per accepted implementation revision; do
not repeat full builds in every role unless diagnosing a failure. Do not delegate
secret handling, destructive actions, or ambiguous scope decisions without user
authorization.

### Git Change Control

Default handoff is an **unstaged working-tree diff** with changed-file and test
evidence. Never run `git add`, `git commit`, `git push`, `git cherry-pick`,
`git merge`, `git rebase`, `git reset`, `git restore`, branch switching, or other
history-changing operations unless the user explicitly requests that exact class
of Git action in the current request. Earlier approval does not persist.

Before any requested migration between worktrees or branches, inspect both
working trees for uncommitted changes, explain the proposed transfer and conflict
strategy, and preserve unrelated user work. Do not make a migration commit unless
the user explicitly asks for a commit; otherwise leave the migrated changes
unstaged for user review.

## Project Structure & Module Organization

禾记（Heji Notes） has two mobile clients sharing one Vault contract. The priority client
is an Android APK for HarmonyOS 4 Mate 60 devices; its Kotlin/Gradle sources
live under `android/`. The existing `entry/` module is the
HarmonyOS 5/6 Stage HAP. App resources and metadata live in `AppScope/`, and
ArkTS code lives under
`entry/src/main/ets/`: UI pages are in `pages/`, data objects in `model/`, and
filesystem or sync logic in `services/`. Resources are grouped under
`entry/src/main/resources/base/`. Hypium tests live in
`entry/src/ohosTest/ets/test/`.

Keep Markdown files and image attachments as the source of truth. Do not add a
database dependency for data that can be derived from the notebook directory.
Both clients must preserve identical Vault paths and relative Markdown links.
`docs/contracts/vault-contract.md` is the sole persisted-data contract for file
layout, attachment paths, recovery, trash, and conflicts.

Documentation lives under `docs/`: `constitution.md` and `product/` govern,
`contracts/` defines cross-client data, `design/` is the experience baseline, and
`specs/` tracks work. Update affected `spec.md`, `plan.md`, and `tasks.md` before
changing scope. Keep ownership singular: use `004` for shared fixtures and
migration checks, `005` for Android delivery, `006` for HAP delivery, and `008`
for traceable Android defects; do not duplicate platform implementation work in
capability specs.

## Design Knowledge Base

Before implementing or reviewing user-visible behavior, read
`docs/design/README.md` and the relevant design file: `design_principles.md` and
`components.md` apply to all screens; `note_editor.md` covers Markdown editing;
`capture_flow.md` covers camera and attachments. Use `review_checklist.md`
before handing off UI or interaction changes. Update a changed design document
before its affected numbered `spec.md`, `plan.md`, and `tasks.md`.

## Version and Release Management

`docs/RELEASE_NOTES.md` records version scope, validation status, and iteration
history. Before producing a candidate build, update the release notes, affected
platform spec and acceptance record, Android `versionName`, and monotonically
increasing `versionCode`. Do not mark a development baseline as released before
its required Mate real-device checks are recorded.

## Packaging Gate

Every change to executable code, resources, manifests, build configuration, or
runtime dependencies must be followed by a fresh package build before handoff.
Changes under `android/` require `./gradlew :app:testDebugUnitTest :app:assembleDebug`
from `android/`; changes under `AppScope/` or `entry/` require
`./scripts/build-hap.sh`. Changes that affect both clients or their shared
runtime contract require both builds. Documentation-only changes do not require
packaging. Report an unavailable build environment as blocked evidence; never
reuse or describe an older artifact as the result of the current change.

## Build, Test, and Development Commands

Prioritize the Android APK defined by spec `005`; use the existing Gradle Wrapper
under `android/` with Java 11 or newer. Use DevEco Studio with a matching
installed SDK for the HarmonyOS 5/6 HAP.

- `ohpm install`; `./scripts/build-hap.sh` builds the entry HAP with
  DevEco-provided Node, Hvigor, and SDK paths.
- Build and run the Hypium test HAP from DevEco Studio on a configured device;
  compilation alone is not device coverage.
- `./gradlew :app:testDebugUnitTest :app:assembleDebug` from `android/` runs the
  Android unit tests and builds the debug APK.
- `./scripts/install-apk.sh` installs the generated APK using an available
  `adb` or `hdc` connection after the device authorizes the debugging key.

On this host, use the DevEco JBR and installed Android SDK explicitly to avoid
an `ANDROID_HOME` lookup failure:

```bash
cd android
JAVA_HOME=/Applications/DevEco-Studio.app/Contents/jbr/Contents/Home \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :app:clean :app:assembleDebug
```

DevEco Studio supplies Node and Hvigor. Do not commit `.hvigor/`, `oh_modules/`,
signed HAPs, or local SDK paths. For CLI builds, `DEVECO_SDK_HOME` must be an
SDK root containing a version directory (for example `HarmonyOS-6.0.1`), not
only `.../sdk/default`; use `--no-daemon` for a stale Hvigor lock. Follow
`docs/specs/005-harmonyos4-android-apk/quickstart.md` for Android and Mate 60
acceptance, and `docs/specs/006-harmonyos5-6-native-hap/quickstart.md` for HAP.

## Coding Style & Naming Conventions

Use two-space indentation in ArkTS and JSON5. Name components and classes in
`PascalCase`, methods and fields in `camelCase`, and tests as
`Behavior.test.ets`. Prefer explicit ArkTS types at API and persistence
boundaries. UI code belongs in pages; direct file or network access belongs in
services. Keep user-facing strings in resources when they are reused.

## Testing Guidelines

Use JUnit for Android logic and Hypium (`describe`, `it`, and `expect`) for
ArkTS logic. Run shared fixtures against both clients. Camera changes require
real-device checks on HarmonyOS 4 APK and HarmonyOS 5/6 HAP: capture a photo,
restart, and confirm the image and relative Markdown link remain valid.

## Commit & Pull Request Guidelines

Use short, imperative English commit subjects (for example, `Add local
attachment storage`) and focused commits. Pull requests must describe
user-visible behavior, list build/test results, link the relevant issue, and
include Mate phone screenshots for UI changes.

## Security & Configuration

Never commit Google Drive tokens, NAS credentials, personal notebooks, signing
keys, or `local.properties`. Use the platform secure store where available;
otherwise keep credentials only in process memory. Request only required
permissions, and redact remote URLs and secrets from logs.
