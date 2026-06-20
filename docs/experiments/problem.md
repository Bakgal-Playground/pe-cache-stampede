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
    val cached = redisTemplate.opsForValue().get("product:$id")
    if (cached != null) return cached as Product

    // 캐시 미스: 아무 보호 없이 바로 DB 조회
    // 동시 요청 N개가 모두 이 라인에 도달한다
    val product = productRepository.findById(id).orElse(null) ?: return null
    meterRegistry.counter("db.query.count", "profile", activeProfile).increment()
    redisTemplate.opsForValue().set("product:$id", product, ttl, TimeUnit.SECONDS)
    return product
}
```

엔드포인트: `GET /problem/products/{id}`

---

## 실험 방법

```bash
# 1. 스택 실행 (SPRING_PROFILES_ACTIVE=problem)
docker compose up -d

# 2. 앱 기동 확인
curl http://localhost:8080/actuator/health

# 3. k6 실행
#    setup(): 캐시 적재 → 11초 대기 (TTL 10초 만료)
#    main:   100 VU 동시 폭격
docker compose run --rm k6

# 4. DB 쿼리 수 확인
curl -s http://localhost:8080/actuator/metrics/db.query.count | jq .measurements
```

---

## 측정 결과

실험 일시: 2026-06-14

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

## 결과 해석

**DB 쿼리 수: 17회 과잉 발생**

100개 동시 요청 중 17개가 캐시 미스 상태에서 동시에 DB를 조회했다.
(캐시 적재 1회 포함 총 18회)

이번 실험에서 에러가 없는 이유:
- 로컬 PostgreSQL 쿼리가 빠름 (avg ~10ms 수준)
- 첫 번째 DB 조회 완료 시 캐시가 채워져 나머지 요청이 캐시를 읽음
- HikariCP 커넥션 풀(10개)이 고갈되기 전에 쿼리가 완료됨

운영 환경에서 위험한 이유:
- 조인/집계 쿼리처럼 DB 응답이 느릴수록 동시 조회 수가 증가
- 커넥션 풀(5개) 초과 시 `connection-timeout: 1000ms` 이후 예외 발생
- HikariCP 예외 → HTTP 500 → 클라이언트 재시도 → 부하 가속 (양성 피드백 루프)
