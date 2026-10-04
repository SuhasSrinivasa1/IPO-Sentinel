package com.iposentinel

/** Fail-closed execution gate. */
class ExecutionGuard {
    fun allowed(
        nseVerified: Boolean,
        growwSymbolResolved: Boolean,
        riskApproved: Boolean,
        userApproved: Boolean
    ): Boolean = nseVerified && growwSymbolResolved && riskApproved && userApproved
}
