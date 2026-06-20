package com.pe.cachestampede.common.metrics

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class DbQueryCounter(
    private val meterRegistry: MeterRegistry,
    private val environment: Environment
) {
    private val activeProfile: String
        get() = environment.activeProfiles.firstOrNull() ?: "unknown"

    fun increment() {
        meterRegistry.counter("db.query.count", "profile", activeProfile).increment()
    }
}
