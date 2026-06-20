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

### Problem 실험 결과 (초기 100 VU 조건)
- DB 쿼리 수: **18회** (기대: 1회)
- p99 응답시간: **212ms**
- 에러율: **0%**

> 개선된 조건(500 VU + 500ms 지연)으로 재실험 필요 — 커넥션 풀 고갈 및 HTTP 500 재현 예상

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

```bash
git checkout main && git checkout -b step/03-solution-v1
```

- `solution/v1/ProductController.kt` — `GET /solution/v1/products/{id}`
- `solution/v1/ProductService.kt` — `ConcurrentHashMap<Long, Mutex>` + Double-checked locking
- 스펙: `docs/EXPERIMENT.md` Step 3
- 구현 후 동일 k6 시나리오로 비교 실험: `PROFILE=solution-v1 docker compose run --rm k6`
- 결과 기록: `docs/experiments/solution-v1.md`

> 참고: `docs/RUNBOOK.md` — 환경 실행, 프로파일 전환, 알려진 이슈 전부 정리됨
