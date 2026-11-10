---
fork: https://github.com/yuan69082-code/Operit
---

# Subscription channel access correction

Based on released +156, main e4c2d62. The subscription picker defaulted to Codex without showing that it was a picker. Gemini and GLM entries were disabled based on service terms; that did not distinguish technical protocol support from the service's permitted-use scope.

This change exposes all choices, adds GLM Coding Plan credentials on its dedicated endpoint, and adapts the public Gemini CLI Code Assist authorization and transport protocol. Service restrictions remain explicit before authorization; Operit identifies itself and does not override service eligibility, billing, or account restrictions. Claude remains unchanged at the user's request. No reference repository is modified or copied wholesale.

Scope: existing encrypted credential vault, account/session binding, normal provider factory, model configuration, streaming/tool continuation, cancellation, metadata and tests. Assistant identity/state/history remain on the existing path. No automatic switch to pay-as-you-go, account rotation, or regeneration after tool execution.

References checked 2026-11-10: Kelivo 4857da6 docs/provider-oauth.md and claude_oauth_adapter.dart; google-gemini/gemini-cli code_assist/oauth2.ts, setup.ts, server.ts, converter.ts; official Gemini CLI terms and BigModel Coding Plan other-tools page. Public installed-client constants are not user credentials. Real account entitlement is not established by source inspection or mocked tests.

GLM quota metadata uses the official ZCode `bigmodelUsageQuotaProvider.ts` monitor path, with a raw plan-key Authorization header (not Bearer). Generation uses Bearer on the separate Coding Plan inference endpoint. Monitor errors leave credentials and chat enabled; token-count fields are retained and credential fields removed. Team-specific account/project selector support is not claimed; quota is the scope returned for the supplied key.

Validation in progress: local Windows/shared-service and verified-release regressions passed (35 tests, 0 failures). The production `SubscriptionAccounts`, `AccountRequestGuard` and `GeminiSubscriptionProtocol` plus the repository's unchanged test entry point `SubscriptionAccountsTest` compiled using Kotlin 2.2.21/JVM 17 target; all 13 tests passed with simulated HTTP and an in-memory credential vault. This isolated JVM check uses only a BuildConfig version stub; it does not establish Android UI/factory/tool-continuation or encrypted-vault behavior. Android compilation, factory/tool-continuation tests and signed build have not yet run on this correction. Real Google/GLM accounts are not available for entitlement or quota checks.

Local Gradle execution stops before compilation: JDK 21's Windows Unix-domain loopback connection fails with `SocketException: Invalid argument: connect`. Contract tests above ran directly through the Kotlin compiler and JUnit, without disabling checks or modifying production code for the environment. Full Android CI still needs to run. GitHub push protection flagged Google's public installed-client ID/value; it requires targeted false-positive review. The repository's protection has not been disabled and values have not been obscured to evade inspection. No correction release exists yet.

Production route: `SubscriptionSettings` -> `ModelConfigManager.updateSubscription` -> existing `AIServiceFactory`/`SubscriptionProviderFactory`. GLM reuses `OpenAIProvider`; Gemini adapts the Code Assist envelope around existing `GeminiProvider` native parts and signatures. Credential refresh is single-flight, generation is bound to an account session and exact inference path, and quota queries cannot choose a paid completion endpoint. The old Codex entry remains in its original flow. Other assistants, subject state, and conversation storage are not replaced.
