package com.pe.cachestampede.common.cache

object CacheKeyResolver {
    fun productKey(id: Long) = "product:$id"
}
