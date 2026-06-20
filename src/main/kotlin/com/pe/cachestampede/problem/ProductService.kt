package com.pe.cachestampede.problem

import com.pe.cachestampede.common.cache.CacheKeyResolver
import com.pe.cachestampede.common.cache.SlowQuerySimulator
import com.pe.cachestampede.common.domain.Product
import com.pe.cachestampede.common.metrics.DbQueryCounter
import com.pe.cachestampede.common.repository.ProductRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class ProductService(
    private val redisTemplate: RedisTemplate<String, Any>,
    private val productRepository: ProductRepository,
    private val dbQueryCounter: DbQueryCounter,
    private val slowQuerySimulator: SlowQuerySimulator,
    @Value("\${cache.ttl}") private val ttl: Long
) {

    fun getProduct(id: Long): Product? {
        val key = CacheKeyResolver.productKey(id)
        val cached = redisTemplate.opsForValue().get(key)
        if (cached != null) return cached as? Product ?: run { redisTemplate.delete(key); null }

        slowQuerySimulator.simulate()
        val product = productRepository.findById(id).orElse(null) ?: return null
        dbQueryCounter.increment()
        redisTemplate.opsForValue().set(key, product, ttl, TimeUnit.SECONDS)
        return product
    }
}
