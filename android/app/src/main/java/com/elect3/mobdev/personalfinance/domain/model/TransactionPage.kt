package com.elect3.mobdev.personalfinance.domain.model

import java.util.Collections

/** A defensive, unmodifiable snapshot with backend-compatible 1-based numbering. */
class TransactionPage(
    items: List<Transaction>,
    val page: Int,
    val pageSize: Int,
    val totalCount: Long,
) {
    val items: List<Transaction> = Collections.unmodifiableList(ArrayList(items))

    init {
        require(page >= 1) { "page must be at least 1" }
        require(pageSize in 1..100) { "pageSize must be within 1..100" }
        require(totalCount >= 0) { "totalCount must not be negative" }
        require(this.items.size <= pageSize) { "items must fit within pageSize" }
    }
}
