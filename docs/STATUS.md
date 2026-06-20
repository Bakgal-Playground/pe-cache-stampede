# 진행 상태

| Step | 내용 | 브랜치 | 상태 |
|------|------|--------|------|
| Step 1 | 공통 환경 구성 | main | ✅ 완료 |
| Step 2 | Problem 구현 + 실험 | step/02-problem | ✅ 완료 |
| Step 3 | Solution V1 — Mutex Lock | step/03-solution-v1 | ⬜ 대기 |
| Step 4 | Solution V2 — TTL Jitter | step/04-solution-v2 | ⬜ 대기 |
| Step 5 | Solution V3 — Cache Warming | step/05-solution-v3 | ⬜ 대기 |
| Step 6 | 테스트 구현 | step/06-tests | ⬜ 대기 |
| Step 7 | README 결과 채우기 | step/07-results | ⬜ 대기 |

---

## Step 2 완료 내역 (2026-06-14)

### 구현
- `problem/ProductController.kt` — `GET /problem/products/{id}`
- `problem/ProductService.kt` — 보호 장치 없는 Cache-Aside + `db.query.count` 계측

### 실험 환경 구성
- Docker Compose 기반 완전 격리 환경 (PostgreSQL 15 / Redis 7 / amazoncorretto:21)
- k6 부하 테스트 서비스 추가 (`grafana/k6:latest`, `profiles: ["test"]`)
- `load-test/stampede.js` — setup() 캐시 적재 → TTL 대기 → 동시 폭격

### 실험 조건 개선
- HikariCP `maximum-pool-size: 5` / `connection-timeout: 1000ms` (전 프로파일 동일)
- `cache.db-delay-ms: 500` — DB 쿼리 지연 시뮬레이션 (애플리케이션 레벨)
- VU 수 env var 주입 가능 (`VUS=500`, 기본값 500)
- 프로파일 전환 env var 지원 (`APP_PROFILE=solution-v1`)

### Problem 실험 결과

| 조건 | DB 쿼리 수 | 동시 조회 비율 | p99 | 에러율 |
|------|-----------|--------------|-----|--------|
| 100 VU (2026-06-14) | 18회 | 17% | 212ms | 0% |
| 500 VU (2026-06-20) | 201회 | 40% | 889ms | 0% |

VU 5배 증가 시 과잉 DB 쿼리 약 12배 증가. 상세 해석: `docs/experiments/problem.md`

### 리팩토링 — 공통 추상화 도입

- `common/cache/CacheKeyResolver.kt` — 캐시 키 포맷 중앙 관리
- `common/metrics/DbQueryCounter.kt` — DB 히트 계측 컴포넌트 (`Environment` 기반 프로파일 감지)
- `common/cache/SlowQuerySimulator.kt` — DB 지연 시뮬레이션 컴포넌트
- `problem/ProductService.kt` 리팩토링 — 공통 컴포넌트 적용, sleep 위치 수정(findById 이전), 안전 캐스트 적용

### 문서화
- `README.md` 전면 개편 (기술 중심, 이모지 제거)
- `docs/experiments/problem.md` — 실험 결과 및 해석
- `docs/RUNBOOK.md` — 에이전트 인수인계 가이드 (프로파일별 실행 명령어, 공통 컴포넌트 사용법, 알려진 이슈)

---

## 다음 단계

**Step 3: Solution V1 — Mutex Lock**

### 1. 브랜치 준비

```bash
# step/02-problem → main merge 후 새 브랜치 생성
git checkout main
git merge step/02-problem
git checkout -b step/03-solution-v1
```

### 2. 구현

파일 2개 생성:
- `src/main/kotlin/com/pe/cachestampede/solution/v1/ProductController.kt` — `GET /solution/v1/products/{id}`
- `src/main/kotlin/com/pe/cachestampede/solution/v1/ProductService.kt` — Mutex Lock 적용

공통 컴포넌트 그대로 주입해서 사용:

```kotlin
@Service
class ProductService(
    private val redisTemplate: RedisTemplate<String, Any>,
    private val productRepository: ProductRepository,
    private val dbQueryCounter: DbQueryCounter,
    private val slowQuerySimulator: SlowQuerySimulator,
    @Value("\${cache.ttl}") private val ttl: Long
) {
    private val mutexMap = ConcurrentHashMap<Long, Mutex>()

    suspend fun getProduct(id: Long): Product? {
        val key = CacheKeyResolver.productKey(id)
        redisTemplate.opsForValue().get(key)
            ?.let { return it as? Product ?: run { redisTemplate.delete(key); null } }

        val mutex = mutexMap.getOrPut(id) { Mutex() }
        mutex.withLock {
            // Double-checked locking
            redisTemplate.opsForValue().get(key)
                ?.let { return it as? Product ?: run { redisTemplate.delete(key); null } }

            slowQuerySimulator.simulate()
            val product = productRepository.findById(id).orElse(null) ?: return null
            dbQueryCounter.increment()
            redisTemplate.opsForValue().set(key, product, ttl, TimeUnit.SECONDS)
            return product
        }
    }
}
```

> Controller는 `suspend fun`을 사용하므로 `@GetMapping` + coroutine 지원 필요.
> `kotlinx-coroutines-core` 의존성은 이미 추가되어 있음 (`build.gradle.kts`).

### 3. 실험

```bash
APP_PROFILE=solution-v1 docker compose up -d --build
PROFILE=solution-v1 VUS=500 docker compose run --rm k6
docker compose exec app sh -c 'curl -s http://localhost:8080/actuator/metrics/db.query.count'
```

### 4. 결과 기록

`docs/experiments/solution-v1.md` 에 결과 기록 (템플릿 준비됨)

> 참고: `docs/RUNBOOK.md` — 환경 실행, 프로파일 전환, 알려진 이슈 전부 정리됨
