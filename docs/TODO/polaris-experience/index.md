---
fork: https://github.com/yuan69082-code/Operit
status: in-progress
---
# Polaris experience integration

Baseline: 5ed3cadb. Preserve concurrent assistant-space and internal-state changes.

User scope: integrate the previously reviewed non-cache capabilities with working product entry points. No prompt-cache changes. Release through the existing signed shared Android update workflow; do not wait for the release build.

## Acceptance checklist

- [ ] Native theme generation, scoped patches, preview/apply/cancel, saved skins, history, import/export and inspection
- [ ] Message-scoped memory evidence
- [ ] Role-scoped worldbook triggers, text transforms, tone preferences and creation wizard
- [ ] Chat commands
- [ ] Collection cards, provenance, project promotion, stateful callable tools and persistent previews
- [ ] Image QR recognition
- [ ] MiniMax voice design, audition and account voice catalog
- [ ] Kelivo migration
- [ ] Cross-object health and tool-chain diagnostics
- [ ] Desktop disk synchronization and native desktop host assessment
- [ ] iOS implementation/distribution prerequisites
- [ ] Validate, integrate latest main, publish and confirm release workflow queued

Existing equivalent features must be reused. Source presence alone does not satisfy completion. Each feature needs a visible entry, real persistence/execution, and checks. Platform work must not be represented as available through an Android APK.

## Integration checkpoint (not acceptance)

Draft PR: https://github.com/yuan69082-code/Operit/pull/21

The current branch connects theme preview and procedural backgrounds, role/worldbook editing, text transformations, persona creation, collection/project editing and preview, voice design, QR recognition, and request evidence. These are implementation checkpoints, not device acceptance. No main merge or app-update release has occurred. Cache behavior is intentionally unchanged.

Build verification runs on GitHub because this environment cannot download Gradle and has no Android SDK. The initial branch build is still running. Remaining compatibility, platform and migration work stays unchecked above.

Further checkpoint: local chat commands, bounded Kelivo import, ownership/resource diagnostics, native bubble tails/corners/stickers, portable theme images, and Windows workspace sync are now implemented. See [native experience](01-native-experience.md) and [migration and sync](02-migration-and-sync.md). Device rendering and final integration acceptance are still pending. iOS distribution and arbitrary CSS selectors remain unimplemented; Electron packaging is under Windows CI validation; do not describe this branch as the entirety of the prior audit.
