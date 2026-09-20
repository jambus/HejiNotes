# Shared Vault Contract

This contract is shared by the Android APK, the HarmonyOS Stage HAP, desktop
Obsidian, and future sync providers. The user-selected Vault is always the
source of truth; neither client creates a second Vault or a required database.

## Layout

```text
<Vault>/
├── Daily Notes/<yyyy-MM-dd>.md
├── assets/<note-file-stem>/<HHmmss>-<xxxx>-o.<ext>
├── assets/<note-file-stem>/<HHmmss>-<xxxx>-c.<ext>
├── assets/<note-file-stem>/<HHmmss>-<xxxx>-v.{mp4|3gp}
├── attachments/                         # legacy, read-only compatibility
├── .trash/                              # local-only deleted notes and folders
└── .markbook/
    └── conflicts/
```

Notes are UTF-8 Markdown. New images are stored below the note parent in
`assets/<note-file-stem>/`; a root daily note `2026-07-30.md` therefore writes
into `assets/2026-07-30/`, while `Projects/2026-07-30.md` writes into
`Projects/assets/2026-07-30/`. Existing root `assets/<stem>/` bundles remain
readable. Links use POSIX relative paths such
as `../assets/2026-07-30/143015-a3f9-c.jpg`. The corrected image is the default
visible reference, while the original remains addressable using the same capture
ID. New capture IDs use the local capture time plus a four-character lowercase
base-36 random suffix. The client checks both target names before creating
either file and retries on a collision.
Existing UUID-based names, including `photo-<uuid>.<ext>`, remain readable for
backward compatibility under the legacy `attachments/` directory.

New videos are original system-camera files, stored without transcoding as
`<HHmmss>-<xxxx>-v.mp4` or `.3gp`. The capture-ID collision check is shared by
photo and video suffixes (`-o`, `-c`, `-v`). A video reference is ordinary
Markdown: `[视频 HH:mm:ss](<relative POSIX path>)`; angle brackets are required
when the relative path contains spaces.

## User-managed files and folders

Users may create Markdown notes and ordinary folders at any depth below the
Vault root. The Vault root itself and the internal root children `.obsidian`,
`.markbook`, `.trash`, `assets`, and `attachments` are not user-manageable file-library
entries. Existing entries that do not meet current creation rules remain
readable and browsable.

New note and folder base names are NFC-normalized and must be unique in their
parent directory using `Locale.ROOT` lowercase comparison; notes and folders
share that namespace. Names may contain Chinese and emoji, but must not be
empty, `.` or `..`, begin with `.`, have leading/trailing whitespace or a
trailing `.`, contain a control character or `/\\<>:\"|?*`, be a Windows reserved
name, or exceed 240 UTF-8 bytes. A new note accepts an optional `.md` suffix
and is persisted with exactly one lowercase `.md`; a folder has no suffix.
Clients never overwrite an existing child to satisfy a create or rename.

Rename and create report both the requested and provider-returned actual name.
If a document provider changes the requested name, the client must retain and
show that actual name rather than silently presenting the request as successful.

Deleting a note or folder means moving the one direct child as a whole to the
Vault-root `.trash/`. A non-empty folder must use the provider's atomic
`moveDocument` semantics; clients must not emulate unsupported moves with a
recursive copy-and-delete. If the move fails or is unsupported, the source and
its contents remain in place. Ordinary rename and move-to-trash do not rewrite
Markdown links and do not automatically delete attachments. The explicit
**Move note with assets** action is the exception: it moves `a.md` and its
exclusive bundle from `<source>/a.md` + `<source>/assets/a/` (or the legacy
Vault-root `assets/a/`) to `<destination>/a.md` + `<destination>/assets/a/`,
then rewrites only that note's Vault-local relative links to the bundle. It
never rewrites inbound links from other notes. Before moving, clients must read
every Markdown file: if any is unreadable, or another note references any file
in the bundle, the move fails without changing the Vault. Source and target
note names, target `assets/` containers, and target bundles must not conflict
or be merged. The target is a normal directory in the same Vault, not an
internal directory. All bundle content moves together; subsequent captures use
the moved note's new parent `assets/<stem>/` bundle.

For a note whose parent relative path is `p`, a new attachment reference is the
POSIX relative path from `p` to `assets/<note-stem>/...`; this applies equally
to root notes, daily notes, and arbitrarily deep ordinary notes. If a folder is
renamed and the configured daily-note directory is that folder or a descendant,
clients synchronously rewrite the matching path prefix to the provider's actual
new name. If moving a folder to `.trash/` removes the configured directory or
one of its ancestors, clients synchronously reset the configuration to the
Vault root and keep a visible warning until the user chooses a new directory.
If a provider reports successful rename but its actual name cannot be resolved,
the client resets an affected daily-note directory to the Vault root rather
than retaining a stale or requested-only path.

## Recovery and Conflicts

Temporary files and transaction markers are prefixed with `.markbook-` and must
be removed or resolved on startup. A completed note or attachment transaction
must not be deleted merely because cleanup was interrupted. An unreferenced
incomplete attachment transaction may be removed after its marker is examined.

For every attachment type, the marker is created and its final attachment name is written and
flushed before any temporary output is created. The writer creates the final attachment(s),
re-reads and hashes the note before insertion to prevent overwriting an
externally changed note, records the final attachment identity and commit stage, saves the note,
then deletes marker and cache. If any
cleanup delete fails, the marker remains. Recovery may remove an unreferenced
incomplete attachment only after every Markdown file that could be scanned was
read successfully; if any Markdown file is unreadable, it must keep the marker
and attachment rather than infer it is unreferenced. Pending external capture
state may persist only note identity/path, note-content SHA-256, caret/scroll,
cache path, final attachment identity and phase—never a private copy of the Markdown body.
After a saved Markdown link is detected, recovery only cleans marker/cache/pending state; it never
replays the insertion. A provider result that returns null or throws after final rename is treated
as unknown and keeps its marker. MP4/3GP acceptance requires a recognized container signature plus
a video track; an output filename or Provider MIME alone is insufficient.

A note-with-assets move is a three-object transaction (note, bundle and
rewritten note text). Its marker under Vault `.markbook/` records source and
target paths, source note SHA-256 and committed stage before the first move.
The client verifies the hash before rewriting, uses only provider whole-folder
moves for a non-empty bundle, verifies all target objects before deleting the
marker, and retains the marker for a null or uncertain provider result. Startup
recovery is idempotent: it completes or rolls back only verified states;
otherwise it preserves both data and the marker. Recover incomplete attachment
transactions before a move begins.

Deletes move notes and folders into `.trash/`; this directory is excluded from remote sync.
Permanent deletion is a separate explicit operation. Attachments are retained until a later
explicit cleanup flow, because they may still be referenced by another note.
An editor media-reference removal is such an explicit cleanup flow only after the user confirms
that both the Markdown reference and its exact referenced attachment file should be removed. The
editor also offers a separate “remove reference only” action that behaves as an ordinary Markdown
edit and never creates a cleanup marker or modifies any attachment file. The combined-delete flow
client must durably create and flush a Vault-local attachment-delete marker before changing the
editor DOM. It saves and re-reads the Markdown first, then strictly scans every readable Markdown
file, including Markdown in `.trash/`, for the exact canonical Vault path. It may permanently
delete only that exact file when the reference is absent everywhere and the file identity and
fingerprint still match the prepared marker. It never infers or deletes sibling `-o`, `-c`, or
other bundle files. Ambiguous syntax, an unreadable file or directory, an external note change,
a replaced attachment, permission loss, or a sync/structural lease conflict keeps the attachment.
The saved Markdown is not rolled back merely because attachment cleanup was retained or failed.

Attachment-delete markers progress through `PREPARED → NOTE_WRITE_INTENT → NOTE_COMMITTED → REFERENCES_VERIFIED →
DELETE_INTENT → DELETED`; marker cleanup follows only a verified terminal state. Recovery before
`NOTE_COMMITTED` never deletes an attachment. `NOTE_WRITE_INTENT` records the intended saved-body
hash before a compare-and-write against the prepared old-body hash. Recovery at or after a committed note repeats the
strict reference and identity checks and is idempotent: it may finish deleting the exact file, or
retain the file and marker when the result is uncertain, but must never delete a referenced or
changed file.

The attachment browser may permanently delete a user-confirmed image or video only through a
separate direct-asset transaction. Its flushed marker records a `DIRECT_CONFIRMED` state plus the
canonical path, provider identity, SHA-256 and size. Recovery may resume from `DIRECT_CONFIRMED`
because it is durable proof of the direct deletion confirmation; it must not fabricate an inline
`NOTE_COMMITTED` stage. Under the same-Vault structural lease, the client strictly scans all
Markdown twice, revalidates the exact file identity, records `DELETE_INTENT`, deletes only that
file, verifies strict absence, records `DELETED`, then removes the marker. A referenced file is a
terminal rejection. Ambiguous or unreadable scans, changed identity, unknown deletion results or
permission loss retain the file; retryable uncertainty retains the marker. Direct deletion never
deletes sibling files or the containing bundle directory.
“Clear trash” permanently deletes only the current contents directly below the Vault-root
`.trash/` after explicit user confirmation. The recycle-bin browser lists only direct children;
Markdown may be inspected read-only, but no in-app restore is implied. Single-item and clear
deletion use the immutable direct-child snapshot shown at confirmation time, so newly added items
remain for a later confirmation. Folders use one Provider whole-folder deletion, never recursive
child deletion. It does not delete `assets/` or `attachments/`, and a partial failure leaves every
undeleted item in place for a later retry.
When both sides changed the same path, keep both versions under
`.markbook/conflicts/` or with a deterministic `冲突-<device>-<timestamp>` suffix.
No client silently overwrites the other version.

## Sync execution and local editing

Remote sync providers execute outside individual editor or settings pages. Their queue state,
progress, result summaries, and provider-specific credentials are device-local, reconstructible
metadata and never Vault content. A running sync must not block reading or editing a local note;
the next sync comparison picks up a later local save. If a remote-only file appears locally while
a sync is running, the provider must preserve the local file and report a conflict rather than
replace it. At most one sync job may modify a Vault at a time. Interrupted jobs are reported as
interrupted and require an explicit retry; they must never be presented as successful.
Local note-with-assets moves are Vault transactions and never depend on remote
configuration or network availability. After a committed move the client records
a provider-neutral `MoveBundle` change containing only Vault identity, source and
target paths, fingerprints and a stable change ID. A running sync and local
structural mutations are mutually exclusive for the same Vault.

The next sync compares the strict local snapshot, strict remote snapshot, last
successful baseline and local change history. It uploads and verifies all new
paths before moving unchanged old remote paths into the provider recycle bin.
Source paths belonging to an unacknowledged move are not treated as ordinary
remote-only downloads. Remote changes after the baseline are preserved as
conflicts. Only a fully successful comparison commits a new baseline and
acknowledges local changes. General deletes do not propagate through this move rule.
