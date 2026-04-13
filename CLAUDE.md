# pe-cache-stampede — CLAUDE.md

상위 프로젝트 규칙은 organization 루트 CLAUDE.md를 따릅니다.
이 파일은 **이 실험에만 적용되는 컨텍스트와 규칙**을 담습니다.

---

## 이 실험의 목적

캐시 TTL이 만료되는 순간, 다수의 요청이 동시에 DB를 직접 조회하는 현상(Cache Stampede)을 재현하고
코드/설계/인프라 레벨에서 각각 해결합니다.

---

## 도메인

상품(Product) 단건 조회 API 하나만 사용합니다.

```kotlin
data class Product(
    val id: Long,
    val name: String,
    val price: Int,
    val stock: Int
)
```

```
GET /products/{id}
→ Redis 캐시 확인
→ 캐시 미스 시 PostgreSQL 조회
→ 캐시 저장 후 응답
```

복잡한 도메인 로직은 만들지 않습니다. 실험의 본질인 캐시 동작에만 집중합니다.

---

## 파일 구조

```
src/main/kotlin/com/pe/cachestampede/
├── common/
│   ├── domain/
│   │   └── Product.kt
│   ├── repository/
│   │   └── ProductRepository.kt        ← JPA Repository (공통)
│   └── config/
│       ├── RedisConfig.kt
│       └── DataInitializer.kt          ← 앱 시작 시 테스트 데이터 삽입
│
├── problem/
│   ├── ProductController.kt
│   └── ProductService.kt               ← 보호 장치 없는 캐시 조회
│
└── solution/
    ├── v1/
    │   ├── ProductController.kt
    │   └── ProductService.kt           ← Mutex Lock
    ├── v2/
    │   ├── ProductController.kt
    │   └── ProductService.kt           ← TTL Jitter
    └── v3/
        ├── ProductController.kt
        ├── ProductService.kt           ← Cache Warming
        └── CacheWarmingScheduler.kt
```

---

## Spring Profile 구성

| Profile       | 실행 버전     | 포트 |
| ------------- | ------------- | ---- |
| `problem`     | 문제 재현     | 8080 |
| `solution-v1` | Mutex Lock    | 8080 |
| `solution-v2` | TTL Jitter    | 8080 |
| `solution-v3` | Cache Warming | 8080 |

프로파일은 `application.yml` 에서 관리합니다.
각 프로파일은 `cache.ttl`, `cache.jitter` 등 캐시 관련 설정값만 다르게 가져갑니다.

---

## 인프라

사용하는 인프라는 두 가지입니다.

- **PostgreSQL**: 상품 데이터 저장
- **Redis**: 캐시 저장소

Prometheus, Grafana는 이 실험에서 사용하지 않습니다.
DB 쿼리 수는 Micrometer Counter로 직접 계측합니다.

---

## 계측 방법

DB 쿼리 횟수를 직접 카운팅합니다.
`ProductRepository.findById()` 가 실제로 호출될 때마다 카운터를 증가시킵니다.

```kotlin
// Service 내부에서 DB 조회 시 반드시 카운터 증가
meterRegistry.counter("db.query.count", "profile", activeProfile).increment()
```

actuator 엔드포인트로 확인합니다.

```
GET /actuator/metrics/db.query.count
```

---

## 이 실험에서 하지 말아야 할 것

- `Thread.sleep()` 으로 캐시 만료 시뮬레이션 금지 → Redis TTL을 짧게 설정해서 자연 만료
- 여러 상품 ID를 섞어서 테스트 금지 → 단일 상품 ID에 트래픽 집중해야 Stampede 재현 가능
- solution 코드에서 problem 코드 재사용 금지 → 각 버전은 완전히 독립적으로 작성
