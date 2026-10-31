# Internal state projection and UI latency

The November 1 device log records a rewind followed by about 5.8 seconds of main-thread stalls. Repeated stacks enter `CompanionContext.compose`, `InternalStateStore.prepare`, and `InternalStateSelection.terms` while ranking stored events.

`CompanionContext.compose` now awaits internal state projection on `Dispatchers.IO`, including its storage reads and existing atomic update. The caller receives the same completed sections before continuing. Selection, stored records, rotation and report frames are unchanged; no records are dropped to shorten the operation.

This addresses the observed UI stall, not remote model latency. In the same log, successful tool follow-up requests waited about 110 and 100 seconds for HTTP response headers, then returned further tool calls. These waits require separate diagnosis.

Validation: inspect the coroutine boundary and run the normal Android CI checks. On device, repeat editing/resending and rollback with the existing large state history, checking that scrolling stays responsive while projection is pending. Confirm normal sends still record one state frame and previews do not persist it. Device validation remains required; this does not establish that every editing delay has the same cause.
