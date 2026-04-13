# [C01] Cache Stampede

> 캐시 TTL이 만료되는 순간, 동시에 들어온 수백 개의 요청이 모두 DB를 직접 조회하여 DB가 과부하 상태에 빠진다

[![블로그](https://img.shields.io/badge/블로그-티스토리-orange)](#)
[![상태](https://img.shields.io/badge/상태-진행중-yellow)](#)

---

## 📌 문제 정의

### 한 줄 요약

Redis 캐시가 만료된 직후 쏟아지는 동시 요청들이 락 없이 전부 DB를 조회하면, DB 커넥션 풀이 고갈되고 전체 응답이 지연된다.

### 발생 조건

- 특정 키에 트래픽이 집중되는 핫 데이터(상품 상세, 이벤트 페이지 등)
- 캐시 TTL 만료와 대량 요청이 동시에 겹치는 순간
- 캐시 미스 이후 DB 조회를 보호하는 장치가 없는 단순 Cache-Aside 패턴

### 장애 흐름

```
캐시 TTL 만료
  → 동시 요청 N개 전부 캐시 미스
  → N개 요청이 동시에 DB 조회
  → DB 커넥션 풀 고갈 (HikariCP maximum-pool-size: 10)
  → 커넥션 대기 타임아웃 발생
  → HTTP 500 / 응답 지연 급증
  → 재시도 요청이 장애를 가속
```

---

## 🏭 실무 시나리오

> 이 문제가 실제 서비스에서 어떤 맥락으로 발생하는지 서술합니다.

**서비스**: 이커머스 플랫폼 — 상품 상세 페이지

**상황**: 오전 9시 타임세일 오픈. SNS에 공유된 특정 상품 링크로 트래픽이 집중된다. 바로 그 순간 해당 상품의 캐시 TTL이 만료된다.

**영향 범위**: 해당 상품 상세 페이지 전체 응답 불가 → DB 커넥션 고갈로 다른 상품 조회까지 연쇄 지연

**방치하면**: 타임아웃된 요청들이 클라이언트 재시도를 유발하고, 재시도가 DB 부하를 더 키우는 양성 피드백 루프(positive feedback loop)로 악화된다.

---

## 🔬 재현 결과

### 실험 환경

- 동시 요청 수: 500개 (k6 VU)
- 데이터 규모: 상품 10건 (단일 ID에 트래픽 집중)
- 인프라: PostgreSQL 1Core/1GB · Redis 0.5Core/512MB · App 2Core/2GB

### 측정 결과

| 측정 항목    | Before (문제) | After v1 (Mutex Lock) | After v2 (TTL Jitter) | After v3 (Cache Warming) |
| ------------ | ------------- | --------------------- | --------------------- | ------------------------ |
| DB 쿼리 수   | ?             | ?                     | ?                     | ?                        |
| p99 응답시간 | ?             | ?                     | ?                     | ?                        |
| 에러율       | ?             | ?                     | ?                     | ?                        |

> 📸 스크린샷: [Before](results/before.png) · [After v1](results/after-v1.png) · [After v2](results/after-v2.png) · [After v3](results/after-v3.png)

---

## 🧱 레벨별 해결 접근

같은 문제를 세 가지 레벨에서 접근합니다.
레벨이 올라갈수록 근본적인 해결이지만, 복잡도와 운영 비용도 함께 올라갑니다.

---

### Level 1. 코드 레벨 — Mutex Lock

**접근 방식**: 캐시 미스 발생 시 상품 ID별 Mutex를 획득한 뒤 DB를 조회한다. 나머지 요청은 Mutex를 기다린 후 캐시에서 읽는다.

**핵심 아이디어**: Double-checked locking으로 단 하나의 요청만 DB에 도달하게 제한한다.

```kotlin
// 핵심 코드 (전체 코드: src/main/kotlin/com/pe/cachestampede/solution/v1/)
private val mutexMap = ConcurrentHashMap<Long, Mutex>()

suspend fun getProduct(id: Long): Product {
    redisTemplate.opsForValue().get("product:$id")?.let { return it as Product }

    val mutex = mutexMap.computeIfAbsent(id) { Mutex() }
    mutex.withLock {
        // Double-check: 락을 기다리는 동안 다른 스레드가 채웠을 수 있음
        redisTemplate.opsForValue().get("product:$id")?.let { return it as Product }

        meterRegistry.counter("db.query.count", "profile", "solution-v1").increment()
        val product = productRepository.findById(id).orElseThrow()
        redisTemplate.opsForValue().set("product:$id", product, Duration.ofSeconds(cacheTtl))
        return product
    }
}
```

**한계**: 단일 인스턴스에서만 유효하다. 다중 인스턴스(수평 확장) 환경에서는 인스턴스 간 Mutex가 공유되지 않아 각 인스턴스마다 DB를 조회한다.

---

### Level 2. 설계 레벨 — TTL Jitter

**접근 방식**: 캐시를 저장할 때 TTL에 랜덤값을 더해 서로 다른 시점에 만료되도록 분산한다. Stampede 자체가 발생하지 않도록 설계를 바꾼다.

**핵심 아이디어**: 동시 만료 자체를 없애면 동시 DB 조회도 없다.

```kotlin
// 핵심 코드 (전체 코드: src/main/kotlin/com/pe/cachestampede/solution/v2/)
val jitter = ThreadLocalRandom.current().nextLong(0, cacheJitter + 1)
val ttl = Duration.ofSeconds(cacheTtl + jitter)
redisTemplate.opsForValue().set("product:$id", product, ttl)
```

**한계**: 캐시가 완전히 비어있는 콜드 스타트(서버 재시작, 캐시 플러시) 상황에서는 첫 요청들이 여전히 동시에 DB를 조회한다. Jitter는 재만료를 분산할 뿐 최초 미스를 막지 못한다.

---

### Level 3. 인프라 레벨 — Cache Warming

**접근 방식**: 스케줄러가 TTL이 절반 이하로 줄어든 키를 미리 갱신한다. 요청이 들어오기 전에 캐시가 항상 채워진 상태를 유지한다.

**핵심 아이디어**: 캐시 미스 자체가 거의 발생하지 않는 상태를 유지한다.

```kotlin
// 핵심 코드 (전체 코드: src/main/kotlin/com/pe/cachestampede/solution/v3/)
@Scheduled(cron = "\${cache.warming.cron}")
fun warmUp() {
    warmingProductIds.forEach { id ->
        val remainingTtl = redisTemplate.getExpire("product:$id", TimeUnit.SECONDS)
        if (remainingTtl < cacheTtl / 2) {
            val product = productRepository.findById(id).orElseThrow()
            redisTemplate.opsForValue().set("product:$id", product, Duration.ofSeconds(cacheTtl))
        }
    }
}
```

**한계**: 워밍 대상이 사전에 정의되어야 한다. 예측 불가능한 핫 키(갑자기 viral된 상품)는 대응하지 못한다. 스케줄러 자체가 DB에 주기적인 쿼리를 발생시키는 부하 요인이 될 수 있다.

---

### 레벨별 비교

| 레벨   | 방법          | 해결 범위                        | 복잡도 | 적합한 상황                              |
| ------ | ------------- | -------------------------------- | ------ | ---------------------------------------- |
| 코드   | Mutex Lock    | 단일 인스턴스 동시 조회 제한     | 낮음   | 소규모 서비스, 단일 서버                 |
| 설계   | TTL Jitter    | 재만료 시점 분산                 | 낮음   | 다중 인스턴스, 상시 트래픽 서비스        |
| 인프라 | Cache Warming | 캐시 미스 자체를 사전 차단       | 중간   | 핫 데이터가 예측 가능한 서비스           |

**최종 판단**

단일 인스턴스 소규모 서비스라면 Mutex Lock(V1)이 가장 빠르게 적용 가능하다.
다중 인스턴스로 수평 확장된 서비스라면 TTL Jitter(V2)를 기본으로 깔고, 트래픽이 예측 가능한 핫 데이터에만 Cache Warming(V3)을 추가하는 조합이 복잡도 대비 가장 실용적이다.
셋 다 캐시가 완전히 날아간 콜드 스타트에는 취약하므로, 배포 후 선제적 워밍업 스크립트를 별도로 운영하는 것이 권장된다.

---

## 💭 고민한 것들

- **분산 환경에서의 Mutex**: Redis의 `SET NX`(Redisson 분산 락)로 확장할 수 있지만, 락 획득 실패 시 재시도 로직과 데드락 방지가 추가 복잡도로 따라온다.
- **Jitter의 적정 범위**: Jitter가 너무 크면 캐시 효율이 떨어지고, 너무 작으면 분산 효과가 없다. 서비스의 초당 요청 수와 TTL 대비 Jitter 비율을 함께 고려해야 한다.
- **Cache Warming 대상 선정**: 상품 ID를 하드코딩하면 운영 부담이 생긴다. 조회 빈도 기반으로 동적으로 워밍 대상을 선정하는 구조가 더 실용적이다.

---

## ▶️ 실행 방법

### 환경 실행

```bash
docker compose up -d
```

### 문제 재현

```bash
# 서버 실행 (문제 버전)
./gradlew bootRun --args='--spring.profiles.active=problem'

# 부하 테스트
k6 run --out csv=results/metrics.csv load-test/scenario.js
```

### 해결 버전 실행

```bash
# Level 1 — Mutex Lock
./gradlew bootRun --args='--spring.profiles.active=solution-v1'

# Level 2 — TTL Jitter
./gradlew bootRun --args='--spring.profiles.active=solution-v2'

# Level 3 — Cache Warming
./gradlew bootRun --args='--spring.profiles.active=solution-v3'

# 부하 테스트 (동일 스크립트 사용)
k6 run --out csv=results/metrics.csv load-test/scenario.js
```

### DB 쿼리 수 확인

```bash
curl http://localhost:8080/actuator/metrics/db.query.count
```

---

## 📁 프로젝트 구조

```
pe-cache-stampede/
├── src/main/kotlin/com/pe/cachestampede/
│   ├── common/               # 공통 도메인, 설정
│   │   ├── domain/           # Product 엔티티
│   │   ├── repository/       # ProductRepository
│   │   └── config/           # RedisConfig, DataInitializer
│   ├── problem/              # 문제 재현 코드 (보호 장치 없음)
│   └── solution/
│       ├── v1/               # Level 1. Mutex Lock
│       ├── v2/               # Level 2. TTL Jitter
│       └── v3/               # Level 3. Cache Warming
├── load-test/
│   └── scenario.js           # k6 부하 테스트 시나리오
├── results/
│   ├── before.png
│   ├── after-v1.png ~ after-v3.png
│   └── metrics.csv
└── docker-compose.yml
```

---

## 🔗 연관 실험

- [C02] [Cache Penetration](#) — 존재하지 않는 키에 대한 반복 조회로 DB가 과부하 상태에 빠지는 패턴
- [C03] [Hot Key](#) — 단일 Redis 키에 트래픽이 극단적으로 집중되어 Redis 노드가 병목이 되는 패턴
