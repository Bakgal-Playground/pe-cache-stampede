# RUNBOOK — pe-cache-stampede

에이전트가 이 프로젝트를 이어받을 때 참고하는 운영 가이드입니다.

---

## 현재 구현 상태

| 단계 | 상태 | 비고 |
|------|------|------|
| Step 1. 공통 환경 구성 | ✅ 완료 | Docker Compose, Redis, PostgreSQL, DataInitializer |
| Step 2. Problem 구현 | ✅ 완료 | `problem/` 패키지, 공통 추상화, 500 VU 실험 완료 |
| Step 3. Solution V1 (Mutex Lock) | ⬜ 대기 | `solution/v1/` 패키지 없음 |
| Step 4. Solution V2 (TTL Jitter) | ⬜ 대기 | `solution/v2/` 패키지 없음 |
| Step 5. Solution V3 (Cache Warming) | ⬜ 대기 | `solution/v3/` 패키지 없음 |

실험 상세 스펙: [docs/EXPERIMENT.md](EXPERIMENT.md)
실험 결과: [docs/experiments/problem.md](experiments/problem.md)

---

## 파일 구조 핵심 경로

```
src/main/kotlin/com/pe/cachestampede/
├── common/
│   ├── cache/
│   │   ├── CacheKeyResolver.kt     # 캐시 키 포맷 중앙 관리 ("product:{id}")
│   │   └── SlowQuerySimulator.kt   # DB 지연 시뮬레이션 (cache.db-delay-ms 설정값)
│   ├── metrics/
│   │   └── DbQueryCounter.kt       # DB 히트 계측 (db.query.count 카운터)
│   ├── config/
│   │   ├── RedisConfig.kt          # RedisTemplate<String, Any> 빈 등록
│   │   └── DataInitializer.kt      # 앱 시작 시 상품 10개 자동 삽입 (id: 1~10)
│   ├── domain/Product.kt           # @Entity data class
│   └── repository/ProductRepository.kt
├── problem/
│   ├── ProductController.kt        # GET /problem/products/{id}
│   └── ProductService.kt           # 보호 장치 없는 Cache-Aside
└── solution/                       # 미구현 (각 step별로 추가)
    ├── v1/                         # Mutex Lock
    ├── v2/                         # TTL Jitter
    └── v3/                         # Cache Warming + Scheduler

load-test/
└── stampede.js                     # k6 시나리오 (setup → TTL 대기 → 동시 폭격)

docs/
├── EXPERIMENT.md                   # 구현 스펙 (각 step 상세)
├── RUNBOOK.md                      # 이 파일
├── STATUS.md                       # 진행 상태
└── experiments/
    ├── problem.md                  # problem 실험 결과
    ├── solution-v1.md              # 미작성
    ├── solution-v2.md              # 미작성
    └── solution-v3.md              # 미작성
```

---

## 프로파일별 실행 명령어

> AI에게 "problem 실행해줘" 또는 "solution-v1 실험 해줘" 라고 하면 아래 명령어 블록을 그대로 실행한다.

### 공통 — 초기화 및 상태 확인

```bash
# 최초 실행 또는 코드 변경 후 (이미지 재빌드 포함)
APP_PROFILE=problem docker compose up -d --build

# 앱 기동 확인 (헬스체크 통과까지 최대 60초)
curl http://localhost:8080/actuator/health

# 전체 종료
docker compose down

# 로그 확인
docker compose logs -f app
```

---

### Problem — 보호 장치 없는 Cache-Aside

```bash
# 1. 실행
APP_PROFILE=problem docker compose up -d --build

# 2. k6 Stampede 실험 (500 VU 기본)
PROFILE=problem VUS=500 docker compose run --rm k6

# 3. DB 쿼리 수 확인
curl -s http://localhost:8080/actuator/metrics/db.query.count | jq .measurements

# 4. 카운터 초기화 (앱 재시작)
docker compose restart app
```

---

### Solution V1 — Mutex Lock

```bash
APP_PROFILE=solution-v1 docker compose up -d --build
PROFILE=solution-v1 VUS=500 docker compose run --rm k6
curl -s http://localhost:8080/actuator/metrics/db.query.count | jq .measurements
```

---

### Solution V2 — TTL Jitter

```bash
APP_PROFILE=solution-v2 docker compose up -d --build
PROFILE=solution-v2 VUS=500 docker compose run --rm k6
curl -s http://localhost:8080/actuator/metrics/db.query.count | jq .measurements
```

---

### Solution V3 — Cache Warming

```bash
APP_PROFILE=solution-v3 docker compose up -d --build
PROFILE=solution-v3 VUS=500 docker compose run --rm k6
curl -s http://localhost:8080/actuator/metrics/db.query.count | jq .measurements
```

---

### k6 옵션

| 환경변수 | 기본값 | 설명 |
|----------|--------|------|
| `PROFILE` | `problem` | k6가 요청할 엔드포인트 프로파일 |
| `VUS` | `500` | 동시 가상 사용자 수 |
| `TTL_WAIT` | `11` | 캐시 TTL 만료 대기 시간(초) |

```bash
# VU 수와 TTL 대기 시간을 직접 지정하는 예
PROFILE=problem VUS=100 TTL_WAIT=11 docker compose run --rm k6
```

k6 시나리오 동작:
1. `setup()`: 캐시 적재(요청 1회) → `TTL_WAIT`초 대기
2. `VUS`개 VU가 동시에 동일 상품 요청

---

## DB 쿼리 수 확인

```bash
curl -s http://localhost:8080/actuator/metrics/db.query.count | jq .measurements
```

주의: 카운터는 `ProductService.getProduct()`가 실제 DB를 조회할 때만 증가한다.
앱 재시작 시 카운터가 0으로 초기화된다.

---

## 주요 설정 결정 사항

### HikariCP (application.yml 전역)
```yaml
maximum-pool-size: 5      # 기본 10에서 축소 — 풀 고갈 유도
minimum-idle: 2
connection-timeout: 1000  # 기본 3000ms에서 축소 — 타임아웃 빠르게 관측
```
모든 프로파일에 동일 적용. 실험 변수를 "캐시 보호 방식"으로만 고정하기 위함.

### cache.db-delay-ms (프로파일별 application.yml)
```yaml
cache:
  db-delay-ms: 500  # DB 쿼리 지연 시뮬레이션 (ms)
```
모든 프로파일(problem, solution-v1~v3)에 동일하게 500ms 설정.
problem 프로파일에서 커넥션 풀 고갈을 유도하고, solution에서 해결됨을 보여준다.

### DDL Auto
```yaml
SPRING_JPA_HIBERNATE_DDL_AUTO: create  # docker-compose.yml 환경변수로 오버라이드
```
application.yml의 `create-drop`을 `create`로 오버라이드.
컨테이너 재시작 시 스키마가 drop되는 문제를 방지.

---

## Solution 구현 규칙

각 solution은 완전히 독립적으로 구현한다. (CLAUDE.md 규칙)

```
GET /solution/v1/products/{id}   # Mutex Lock
GET /solution/v2/products/{id}   # TTL Jitter
GET /solution/v3/products/{id}   # Cache Warming
```

- `@Profile` 어노테이션 없이 패키지로만 분리
- `ttl` 설정값은 `@Value("\${cache.ttl}")`로 주입
- `common/` 패키지의 세 컴포넌트를 반드시 주입해서 사용

```kotlin
class ProductService(
    private val redisTemplate: RedisTemplate<String, Any>,
    private val productRepository: ProductRepository,
    private val dbQueryCounter: DbQueryCounter,       // DB 히트 계측
    private val slowQuerySimulator: SlowQuerySimulator, // DB 지연 시뮬레이션
    @Value("\${cache.ttl}") private val ttl: Long
) {
    fun getProduct(id: Long): Product? {
        val key = CacheKeyResolver.productKey(id)
        // ... 각 버전의 concurrency 보호 로직 구현
        slowQuerySimulator.simulate()
        val product = productRepository.findById(id).orElse(null) ?: return null
        dbQueryCounter.increment()
        redisTemplate.opsForValue().set(key, product, ttl, TimeUnit.SECONDS)
        return product
    }
}
```

- solution/v1은 코루틴 Mutex 사용 (`kotlinx-coroutines-core` 의존성 이미 추가됨)
- 구현 완료 후 `docs/experiments/solution-vN.md`에 결과 기록

---

## 알려진 이슈 및 해결책

| 이슈 | 원인 | 해결 |
|------|------|------|
| 헬스체크 실패 | `amazoncorretto:21`에 `wget` 없음 | `curl -f` 로 교체 |
| `relation "product" does not exist` | `create-drop`으로 컨테이너 재시작 시 스키마 drop | docker-compose.yml에서 `DDL_AUTO=create` 오버라이드 |
| `port 6379 already allocated` | `docker compose up -d k6`는 의존 서비스도 재시작 시도 | k6는 `docker compose run --rm k6`로만 실행 |

---

## 커밋 전략

현재 브랜치: `step/02-problem` (커밋 완료, main merge 대기)

```bash
# Step 2 마무리 — main merge
git checkout main
git merge step/02-problem

# Step 3 시작
git checkout -b step/03-solution-v1
```
