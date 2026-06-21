package com.pe.cachestampede.common.cache

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class SlowQuerySimulator(
    @Value("\${cache.db-delay-ms:0}") private val dbDelayMs: Long
) {
    fun simulate() {
        if (dbDelayMs > 0) Thread.sleep(dbDelayMs)
    }
}
