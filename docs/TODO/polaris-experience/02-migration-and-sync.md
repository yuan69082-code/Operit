# Migration, diagnostics and desktop sync checkpoint

Chat commands have actual local handlers for role/model selection, notes, workspace binding, branching and confirmed rewind. Unrecognized slash text continues through normal send. A busy chat does not execute commands.

Kelivo ZIP import preserves the original archive and extracted media. It creates separate roles/configurations, imports chat records and worldbook entries, stores assistant memories as library entries, and adds compatible remote MCP definitions disabled. The preview names unsupported Kelivo layouts/message-template features; additional per-item conversion limitations appear in the result. This is not a bit-for-bit restore of another application. Imports use source markers for retry recognition; there is no cross-store atomic transaction, so a failure report explicitly says completed writes remain.

Data checks report missing role ownership, model configurations, collection provenance and theme resources. They do not silently repair or delete.

Windows workspace UI is included in the existing Windows ZIP. It performs three-way text-file synchronization, previews writes/deletions, requires individual choices for conflicts, and refuses a stale disk fingerprint. It excludes build/dependency/VCS directories, rejects symlinks and non-UTF-8 files, and is protected by the existing server bearer token.

Checks performed: four focused workspace synchronization tests passed; both desktop HTML scripts pass JavaScript syntax checks. Existing desktop suite has an environment-specific failure because os.networkInterfaces is unavailable in this sandbox; this is not reported as a pass. Android JVM tests passed for the prior checkpoint ac6548de; new Android changes need a new run.

Platform limit: this branch does not contain a distributable iOS application or Electron shell. Those are not delivered through an Android in-app update. Full arbitrary CSS selector styling also cannot be represented as supported by this native Compose implementation. These items remain explicit open scope, not fake completed modules.
