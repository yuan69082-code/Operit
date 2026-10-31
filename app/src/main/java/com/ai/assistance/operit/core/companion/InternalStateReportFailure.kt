package com.ai.assistance.operit.core.companion

import com.ai.assistance.operit.R
import org.json.JSONException
import java.io.IOException

/** Fixed diagnostics only: exception messages may contain private report or source text. */
enum class InternalStateReportFailure(val messageRes: Int) {
    FRAME(R.string.state_report_frame),
    SOURCE(R.string.state_report_source),
    REVISION(R.string.state_report_revision),
    DEFINITION(R.string.state_report_definition),
    CONFLICT(R.string.state_report_conflict),
    FORMAT(R.string.state_report_format),
    DISABLED(R.string.state_report_disabled),
    STORAGE(R.string.state_report_storage),
    INTERNAL(R.string.state_report_internal),
    NO_FRAME(R.string.state_report_no_frame);

    val code: String get() = "ISR_" + name

    companion object {
        fun classify(failure: Exception): InternalStateReportFailure = when (failure) {
            is InternalStateReportRejected -> failure.reason
            is IOException -> STORAGE
            is JSONException, is IllegalArgumentException -> FORMAT
            else -> INTERNAL
        }
    }
}

class InternalStateReportRejected(val reason: InternalStateReportFailure) : IllegalArgumentException(reason.code)

internal fun requireStateReport(condition: Boolean, reason: InternalStateReportFailure) {
    if (!condition) throw InternalStateReportRejected(reason)
}
