# Solution V1 — Mutex Lock

## 목적

`ConcurrentHashMap<Long, Mutex>` + Double-checked locking으로 동일 상품 ID에 대한 동시 DB 조회를 1회로 줄인다.
Problem 실험(500 VU, DB 쿼리 201회)과 동일 조건에서 DB 쿼리 수가 1회에 수렴하는지 확인한다.

---

## 핵심 코드

```kotlin
// src/main/kotlin/com/pe/cachestampede/solution/v1/ProductService.kt
private val mutexMap = ConcurrentHashMap<Long, Mutex>()

suspend fun getProduct(id: Long): Product? {
    val key = CacheKeyResolver.productKey(id)
    redisTemplate.opsForValue().get(key)
        ?.let { return it as? Product ?: run { redisTemplate.delete(key); null } }

    val mutex = mutexMap.getOrPut(id) { Mutex() }
    mutex.withLock {
        // Double-checked locking: 락 획득 후 캐시 재확인
        redisTemplate.opsForValue().get(key)
            ?.let { return it as? Product ?: run { redisTemplate.delete(key); null } }

        slowQuerySimulator.simulate()
        val product = productRepository.findById(id).orElse(null) ?: return null
        dbQueryCounter.increment()
        redisTemplate.opsForValue().set(key, product, ttl, TimeUnit.SECONDS)
        return product
    }
}
```

엔드포인트: `GET /solution/v1/products/{id}`

---

## 실험 방법

```bash
# 1. 스택 실행
APP_PROFILE=solution-v1 docker compose up -d --build

# 2. k6 실행 (problem과 동일 조건)
PROFILE=solution-v1 VUS=500 docker compose run --rm k6

# 3. DB 쿼리 수 확인
docker compose exec app sh -c 'curl -s http://localhost:8080/actuator/metrics/db.query.count'
```

---

## 측정 결과

| 측정 항목 | 값 |
|---------|-----|
| DB 쿼리 수 (TTL 만료 후) | — |
| 동시 조회 비율 | — |
| p99 응답시간 | — |
| p95 응답시간 | — |
| p90 응답시간 | — |
| 평균 응답시간 | — |
| 최대 응답시간 | — |
| 에러율 | — |
| 총 요청 수 | — |

k6 시나리오: 500 VU / shared-iterations / TTL 만료 후 즉시 실행
실험 조건: `cache.db-delay-ms=500` / HikariCP `maximum-pool-size=5` / `connection-timeout=1000ms`

---

## Problem 대비 비교

| 항목 | Problem (500 VU) | Solution V1 (500 VU) |
|------|-----------------|----------------------|
| DB 쿼리 수 | 201회 | — |
| 동시 조회 비율 | 40% | — |
| p99 응답시간 | 889ms | — |
| 에러율 | 0% | — |

---

## 결과 해석

> 실험 완료 후 작성
