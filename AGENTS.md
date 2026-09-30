# Repository Guidelines

## Execution, Review, and Git Control

Classify implementation risk in the first progress update. File count alone is
not a risk signal. The coordinator always owns scope, integration, validation,
and the final report.

| Level | Scope | Model and workflow |
| --- | --- | --- |
| L0 | Questions, read-only work, typo fixes, isolated documentation, formatting, inventories, boilerplate, or test data | Simple mode: GPT-6 Luna, `max`; one agent, no separate review. |
| L1 | Routine implementation, local UI, or pure logic without persistence, permission, or contract effects | Simple mode: GPT-5.6 Sol, `medium`; one agent performs discovery, implementation, affected tests, and reporting. |
| L2 | Architecture or design review, hard bugs, compatibility, lifecycle issues, or substantial/multi-screen UX without Vault, sync, concurrency, or security risk | Multi-agent mode: GPT-5.6 Sol, `medium`, or GPT-6 Astra, `low`, reviews; GPT-5.6 Sol, `medium`, implements and validates; the original reviewer performs final review. |
| L3 | Vault data, deletion, permissions, sync, concurrency, migrations, cross-client contracts, or security | Multi-agent mode: GPT-5.6 Sol, `medium`, or GPT-6 Astra, `low`, reviewer → GPT-5.6 Sol, `medium`, developer → independent GPT-5.6 Sol, `medium`, tester → original reviewer. Use GPT-6 Astra, `medium`, as coordinator/reviewer only for exceptionally complex work. |

Use GPT-family models only in Codex. Treat an unknown environment as Codex until
confirmed; do not infer it from paths, editor files, or installed integrations.
In a confirmed Google ecosystem IDE, Gemini 3.8 Flash may execute independently
verifiable L1/L2 work with named files, fixed acceptance criteria, and exact
validation commands. It never owns coordination, architecture, integration,
high-risk review, Vault/data/permission/sync/security work, secrets, destructive
actions, or the final report. Treat its output as an untrusted working-tree
proposal until the coordinator inspects the diff and reproduces the checks.
Do not use Terra as a default or required model.

For multi-agent work, state the level, roles, file ownership, and handoffs before
delegation. Give each agent a compact card containing the objective, scope and
exclusions, files, hard constraints, and exact acceptance commands. Do not pass
full history when the card is sufficient. Parallel agents must have independent
files and decisions; never let them edit the same files concurrently. Preserve
user changes and keep task records singular.

For affected L2/L3 work, update design, contract, spec, plan, and task documents
before implementation. Review findings must cite files and acceptance criteria.
Developers run fast affected checks. The independent L3 tester does not modify
production code, distinguishes automated from real-device evidence, and runs
the final full suite once per accepted revision, including after repairs. Do not
delegate secrets, destructive actions, or ambiguous scope without user approval.

### Git Change Control

Default delivery is an unstaged working-tree diff plus changed-file and test
evidence. Do not stage, commit, push, cherry-pick, merge, rebase, reset, restore,
switch branches, or otherwise change Git history unless the current user request
explicitly authorizes that class of operation; earlier approval does not persist.

Before an authorized worktree or branch migration, inspect both working trees,
explain the transfer and conflict strategy, and preserve unrelated work. Unless
the user explicitly requests a commit, leave migrated changes unstaged.

## Repository and Vault Invariants

Heji Notes has two clients sharing one Vault contract:

- `android/`: priority Android APK for HarmonyOS 4 Mate 60 devices.
- `AppScope/` and `entry/`: HarmonyOS 5/6 Stage HAP. ArkTS UI, models, and
  filesystem/sync services live under `entry/src/main/ets/{pages,model,services}`;
  resources under `entry/src/main/resources/base/`; Hypium tests under
  `entry/src/ohosTest/ets/test/`.

Markdown files and image attachments are the source of truth. Do not add a
database for data derivable from the notebook directory. Both clients must keep
identical Vault paths and relative Markdown links. `docs/contracts/vault-contract.md`
is the sole persisted-data contract for layout, attachments, recovery, trash,
and conflicts.

Documentation roles are: `docs/constitution.md` and `docs/product/` govern;
`docs/contracts/` defines cross-client data; `docs/design/` defines experience;
`docs/specs/` tracks delivery. Keep ownership singular: `004` owns shared
fixtures/migration checks, `005` Android delivery, `006` HAP delivery, and `008`
traceable Android defects. Do not duplicate platform work in capability specs.

## Design and Specification Workflow

Before implementing or reviewing user-visible behavior, read
`docs/design/README.md`, `design_principles.md`, `components.md`, and the relevant
topic file (`note_editor.md` for Markdown editing; `capture_flow.md` for camera
and attachments). Use `review_checklist.md` before UI handoff.

For scope or UX changes, update the relevant design or contract first, followed
by the affected numbered `spec.md`, `plan.md`, and `tasks.md`.

## Validation and Packaging

Every executable-code, resource, manifest, build-configuration, or runtime-
dependency change requires a fresh package build before handoff:

| Changed area | Required command |
| --- | --- |
| `android/` | From `android/`: `./gradlew :app:testDebugUnitTest :app:assembleDebug` |
| `AppScope/` or `entry/` | `./scripts/build-hap.sh` |
| Both clients or shared runtime contract | Run both commands above |
| Documentation only | No package build |

Report an unavailable build environment as blocked evidence. Never reuse or
describe an older artifact as the result of current changes. Compilation is not
device coverage. Use JUnit for Android logic and Hypium for ArkTS logic, and run
shared fixtures against both clients. Camera changes require real-device checks
on the HarmonyOS 4 APK and HarmonyOS 5/6 HAP: capture a photo, restart, and
confirm the image and relative Markdown link remain valid.

Use the Gradle wrapper with Java 11 or newer. On this host, set DevEco JBR as
`JAVA_HOME` and `$HOME/Library/Android/sdk` as `ANDROID_HOME`. DevEco supplies
Node and Hvigor. For CLI HAP builds, `DEVECO_SDK_HOME` must contain a version
directory such as `HarmonyOS-6.0.1`; use `--no-daemon` for stale Hvigor locks.
See `docs/specs/005-harmonyos4-android-apk/quickstart.md` for Android/Mate 60
acceptance and `docs/specs/006-harmonyos5-6-native-hap/quickstart.md` for HAP.

## Code, Release, and Security

Use two-space indentation in ArkTS and JSON5; `PascalCase` for components and
classes; `camelCase` for methods and fields; and `Behavior.test.ets` for tests.
Use explicit ArkTS types at API and persistence boundaries. Keep UI in pages,
file/network access in services, and reusable user-facing strings in resources.

Before a candidate build, update `docs/RELEASE_NOTES.md`, the affected platform
spec and acceptance record, Android `versionName`, and monotonically increasing
`versionCode`. Do not mark a baseline released until required Mate real-device
checks are recorded.

Use short imperative English commit subjects and focused commits. Pull requests
must describe visible behavior, list build/test results, link the issue, and
include Mate screenshots for UI changes.

Never commit `.hvigor/`, `oh_modules/`, signed HAPs, local SDK paths,
`local.properties`, credentials, tokens, signing keys, or personal notebooks.
Use platform secure storage when available; otherwise keep credentials only in
process memory. Request only necessary permissions and redact secrets and remote
URLs from logs.
