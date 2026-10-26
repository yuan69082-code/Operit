# Render inspection and collection/workspace links

User narrowed remaining scope: defer Kelivo conversion extensions, arbitrary CSS and iOS. Preserve all other windows' changes. This step adds measured native rendering inspection and collection references plus reversible workspace handoff.

Render inspection must report currently mounted component bounds and resolved colors/shapes, distinguish absent components from errors, and never return another role's view. UI and AI tool share the same inspection result.

References retain source ownership and provenance, support directory/search/read, explicit promotion into editable project files, and pinning a file as a reference. Workspace export uses a new owned directory; pull checks both project version and exported file baseline before accepting changes. Never overwrite unrelated workspace files. UI navigation must actually open the existing workspace editor.

Validation: add behavioral tests for ownership, reference promotion, conflicts and live-render record lifecycle; run Android checks via CI. Merge and trigger the existing update release; do not wait for the final release build.

Implementation: native mounted-node registry is scoped by role/group, removes disposed nodes and reads current properties without requiring a relayout. Project WebView inspection records computed styles, bounds, errors and live dynamic form state; insertion preserves doctype. The same data is exposed to the user and tools.

Local executable JS checks passed for computed colors/bounds, dynamic forms, password-value omission and runtime error capture. JVM tests cover source deletion, ownership, concurrent edits, workspace round trip and render lifecycle. Remote compile/test result pending.
