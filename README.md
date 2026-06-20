# Cache Stampede

> 캐시 TTL 만료 시점에 동시 요청 전체가 DB를 직접 조회하여 커넥션 풀이 고갈되는 현상을 재현하고 해결한다

[![상태](https://img.shields.io/badge/상태-진행중-yellow)](#)

---

## 현상

Cache-Aside 패턴에서 캐시 키가 만료되는 순간, 보호 장치 없이 들어오는 N개의 동시 요청이 모두 DB를 직접 조회한다.

```
캐시 TTL 만료
  → 동시 요청 N개 전부 캐시 미스
  → N개 요청이 동시에 DB 조회
  → DB 커넥션 풀 고갈 (HikariCP maximum-pool-size: 10)
  → 커넥션 대기 타임아웃
  → HTTP 500 / 응답 지연 급증
```

---

## 실험 환경

### 인프라

| 컴포넌트    | 이미지                     | CPU     | Memory | 포트 |
|------------|---------------------------|---------|--------|------|
| App        | amazoncorretto:21 (런타임) | 2Core   | 2GB    | 8080 |
| PostgreSQL | postgres:15                | 1Core   | 1GB    | 5432 |
| Redis      | redis:7-alpine             | 0.5Core | 512MB  | 6379 |
| k6         | grafana/k6:latest          | -       | -      | -    |

### 설정값

| 항목 | 값 |
|------|-----|
| HikariCP `maximum-pool-size` | 10 |
| HikariCP `connection-timeout` | 3000ms |
| Redis TTL | 10초 (problem / v1 기준) |
| 테스트 상품 | ID=1 (단일 키 집중) |
| 초기 데이터 | 상품 10개 (앱 시작 시 자동 삽입) |

### 환경 구축

```bash
# 이미지 빌드 및 전체 스택 실행
docker compose up -d

# 앱 기동 확인
curl http://localhost:8080/actuator/health

# 프로파일 전환 시 docker-compose.yml의 SPRING_PROFILES_ACTIVE 수정 후 재시작
# problem | solution-v1 | solution-v2 | solution-v3
docker compose restart app

# 부하 테스트 실행
docker compose run --rm k6
# solution 전환: PROFILE=solution-v1 docker compose run --rm k6
```

---

## 실험 목록

| 단계 | 접근 | 상세 |
|------|------|------|
| Problem | 보호 장치 없는 Cache-Aside | [problem.md](docs/experiments/problem.md) |
| Solution V1 | Mutex Lock (코드 레벨) | [solution-v1.md](docs/experiments/solution-v1.md) |
| Solution V2 | TTL Jitter (설계 레벨) | [solution-v2.md](docs/experiments/solution-v2.md) |
| Solution V3 | Cache Warming (인프라 레벨) | [solution-v3.md](docs/experiments/solution-v3.md) |

---

## 결과 비교

| 측정 항목    | Problem | V1 Mutex Lock | V2 TTL Jitter | V3 Cache Warming |
|-------------|:-------:|:-------------:|:-------------:|:----------------:|
| DB 쿼리 수   | **18회** | 측정 예정 | 측정 예정 | 측정 예정 |
| p99 응답시간 | **212ms** | 측정 예정 | 측정 예정 | 측정 예정 |
| 에러율       | **0%** | 측정 예정 | 측정 예정 | 측정 예정 |

> DB 쿼리 수 기대값: Problem N회 → Solution 1회 (이상적)

---

## 트레이드오프

| 방법 | 해결 범위 | 한계 |
|------|----------|------|
| Mutex Lock | 단일 인스턴스 동시 조회 제한 | 다중 인스턴스 환경에서 무효 |
| TTL Jitter | 만료 시점 분산 | 콜드 스타트 시 첫 요청 보호 불가 |
| Cache Warming | 캐시 미스 자체 차단 | 예측 불가한 핫 키 대응 불가 |
