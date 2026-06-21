# Problem — Cache Stampede 재현

## 목적

보호 장치 없는 Cache-Aside 패턴에서 캐시 TTL 만료 직후 동시 요청이 몰릴 때 DB 쿼리가 몇 번 발생하는지 측정한다.

기대 동작 (정상): 캐시 미스 → DB 조회 1회 → 캐시 저장 → 나머지 요청은 캐시에서 읽음
실제 동작 (문제): 캐시 미스 → 동시 요청 N개 전부 DB 직접 조회

---

## 핵심 코드

```kotlin
// src/main/kotlin/com/pe/cachestampede/problem/ProductService.kt
fun getProduct(id: Long): Product? {
    val key = CacheKeyResolver.productKey(id)
    val cached = redisTemplate.opsForValue().get(key)
    if (cached != null) return cached as? Product ?: run { redisTemplate.delete(key); null }

    // 캐시 미스: 아무 보호 없이 바로 DB 조회
    // 동시 요청 N개가 모두 이 라인에 도달한다
    slowQuerySimulator.simulate()   // 500ms 지연 (슬로우 쿼리 시뮬레이션)
    val product = productRepository.findById(id).orElse(null) ?: return null
    dbQueryCounter.increment()
    redisTemplate.opsForValue().set(key, product, ttl, TimeUnit.SECONDS)
    return product
}
```

엔드포인트: `GET /problem/products/{id}`

---

## 실험 방법

```bash
# 1. 스택 실행
APP_PROFILE=problem docker compose up -d --build

# 2. k6 실행 (500 VU)
PROFILE=problem VUS=500 docker compose run --rm k6

# 3. DB 쿼리 수 확인
docker compose exec app sh -c 'curl -s http://localhost:8080/actuator/metrics/db.query.count'
```

---

## 측정 결과

### 실험 1 — 100 VU (2026-06-14, 초기 실험)

| 측정 항목 | 값 |
|---------|-----|
| DB 쿼리 수 (TTL 만료 후) | **18회** (캐시 적재 1회 + 동시 조회 17회) |
| p99 응답시간 | **212ms** |
| p95 응답시간 | 145ms |
| 평균 응답시간 | 115ms |
| 에러율 | 0% |
| 총 요청 수 | 101회 (setup 1 + VU 100) |

k6 시나리오: 100 VU / shared-iterations / TTL 만료 후 즉시 실행 (VUS=100 override, 스크립트 기본값 500)

---

### 실험 2 — 500 VU (2026-06-20, 개선된 조건)

| 측정 항목 | 값 |
|---------|-----|
| DB 쿼리 수 (TTL 만료 후) | **201회** (캐시 적재 1회 + 동시 조회 200회) |
| 동시 조회 비율 | **40%** (500개 요청 중 200개가 DB 직접 조회) |
| p99 응답시간 | **889ms** |
| p95 응답시간 | 881ms |
| p90 응답시간 | 873ms |
| 평균 응답시간 | 474ms |
| 최대 응답시간 | 892ms |
| 에러율 | 0% |
| 총 요청 수 | 501회 (setup 1 + VU 500) |

k6 시나리오: 500 VU / shared-iterations / TTL 만료 후 즉시 실행
실험 조건: `cache.db-delay-ms=500` / HikariCP `maximum-pool-size=5` / `connection-timeout=1000ms`

---

## 결과 해석

### 실험 2 기준 (500 VU)

**DB 쿼리 수: 200회 과잉 발생**

500개 동시 요청 중 200개(40%)가 캐시 미스 상태에서 DB를 조회했다.
`slowQuerySimulator.simulate()` (500ms)가 `findById()` 이전에 위치하여, 캐시 미스를 확인한 모든 요청이 500ms 동안 대기한다. 이 대기 시간 동안 캐시는 여전히 비어 있으므로, sleep 이후 깨어난 모든 요청이 동시에 DB를 조회한다.

HikariCP가 5개 커넥션으로 순차 처리(5개씩 배치)했기 때문에 에러는 발생하지 않았다. 실제 운영 환경에서 위험한 이유:

- DB 응답이 느릴수록 대기 중인 동시 조회 수가 증가
- 커넥션 풀(5개) 초과 시 `connection-timeout: 1000ms` 이후 예외 발생
- HikariCP 예외 → HTTP 500 → 클라이언트 재시도 → 부하 가속 (양성 피드백 루프)

### 실험 1 vs 실험 2 비교

| | 실험 1 (100 VU) | 실험 2 (500 VU) |
|---|---|---|
| 과잉 DB 쿼리 | 17회 | 200회 |
| 동시 조회 비율 | 17% | 40% |
| p99 응답시간 | 212ms | 889ms |
| 에러율 | 0% | 0% |

VU 수가 5배 증가했을 때 과잉 DB 쿼리는 약 12배 증가했다.
VU가 더 많을수록 캐시 미스 윈도우(500ms) 안에 더 많은 요청이 몰리기 때문이다.
