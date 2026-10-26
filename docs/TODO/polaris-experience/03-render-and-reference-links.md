# Render inspection and collection/workspace links

User narrowed remaining scope: defer Kelivo conversion extensions, arbitrary CSS and iOS. Preserve all other windows' changes. This step adds measured native rendering inspection and collection references plus reversible workspace handoff.

Render inspection must report currently mounted component bounds and resolved colors/shapes, distinguish absent components from errors, and never return another role's view. UI and AI tool share the same inspection result.

References retain source ownership and provenance, support directory/search/read, explicit promotion into editable project files, and pinning a file as a reference. Workspace export uses a new owned directory; pull checks both project version and exported file baseline before accepting changes. Never overwrite unrelated workspace files. UI navigation must actually open the existing workspace editor.

Validation: add behavioral tests for ownership, reference promotion, conflicts and live-render record lifecycle; run Android checks via CI. Merge and trigger the existing update release; do not wait for the final release build.
