---
fork: https://github.com/yuan69082-code/Operit
---

# Subscription channel access correction

Based on released +156, main e4c2d62. The subscription picker defaulted to Codex without showing that it was a picker. Gemini and GLM entries were disabled based on service terms; that did not distinguish technical protocol support from the service's permitted-use scope.

This change exposes all choices, adds GLM Coding Plan credentials on its dedicated endpoint, and adapts the public Gemini CLI Code Assist authorization and transport protocol. Service restrictions remain explicit before authorization; Operit identifies itself and does not override service eligibility, billing, or account restrictions. Claude remains unchanged at the user's request. No reference repository is modified or copied wholesale.

Scope: existing encrypted credential vault, account/session binding, normal provider factory, model configuration, streaming/tool continuation, cancellation, metadata and tests. Assistant identity/state/history remain on the existing path. No automatic switch to pay-as-you-go, account rotation, or regeneration after tool execution.

References checked 2026-11-10: Kelivo 4857da6 docs/provider-oauth.md and claude_oauth_adapter.dart; google-gemini/gemini-cli code_assist/oauth2.ts, setup.ts, server.ts, converter.ts; official Gemini CLI terms and BigModel Coding Plan other-tools page. Public installed-client constants are not user credentials. Real account entitlement is not established by source inspection or mocked tests.

Validation and final release evidence will be recorded here after execution.
