# Native experience implementation checkpoint

Previously the app exposed manual native theme settings. This step adds role/group theme generation, field patches, preview keep/cancel, libraries, snapshots and portable image-bearing theme export. Generated materials use deterministic Canvas backgrounds and existing Compose preference consumers. This is not a CSS runtime; unsupported fields fail validation.

Role expression rules have separate storage, editable tone/worldbook entries, scanned-message depth and priority. Input transforms preserve stored messages. Output transforms apply after completion. Protocol XML and fenced code are protected. Worldbook segments are replaced on tool follow-ups rather than duplicated.

Collection cards retain source chat/timestamp, editable title/tags/content, multi-file conversion, project export, isolated preview origins and persisted preview state. Script cards require manual enablement and inherit tool execution identity. Files use atomic writes and optimistic version checks.

Request evidence records the exact initial recall segment and observed tool lifecycle. Missing vector scores are not invented. Diagnostic persistence must not alter request behavior.

Validation: whitespace check passes. Android compile and device acceptance remain pending. Do not mark this step DONE until actual verification and remaining failure cases are addressed.
