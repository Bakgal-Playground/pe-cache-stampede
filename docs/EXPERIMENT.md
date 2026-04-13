# pe-cache-stampede — EXPERIMENT.md

이 파일은 각 구현 단계의 **상세 스펙**을 담습니다.
Claude Code는 이 파일을 참고해서 구현합니다.

---

## Step 1. 공통 환경 구성

### 1-1. build.gradle.kts

아래 의존성을 포함합니다.

```
spring-boot-starter-web
spring-boot-starter-data-jpa
spring-boot-starter-data-redis
spring-boot-starter-actuator
micrometer-registry-prometheus
postgresql
kotlin-reflect
```

테스트 의존성:

```
spring-boot-starter-test
testcontainers (postgresql, redis)
kotest-runner-junit5
kotest-extensions-spring
mockk
```

### 1-2. docker-compose.yml

아래 서비스를 포함합니다.

**postgres**

- image: postgres:15
- database: stampede
- user: postgres / password: postgres
- port: 5432
- 리소스 제한: CPU 1Core / Memory 1GB
- 헬스체크 포함

**redis**

- image: redis:7-alpine
- port: 6379
- 리소스 제한: CPU 0.5Core / Memory 512MB
- 헬스체크 포함

**app**

- build: 현재 디렉터리
- port: 8080
- depends_on: postgres, redis (헬스체크 통과 후)
- 리소스 제한: CPU 2Core / Memory 2GB
- environment로 DB, Redis 접속 정보 주입

### 1-3. application.yml

```yaml
spring:
  profiles:
    active: problem

  datasource:
    url: jdbc:postgresql://localhost:5432/stampede
    username: postgres
    password: postgres
    hikari:
      maximum-pool-size: 10
      minimum-idle: 5
      connection-timeout: 3000

  jpa:
    hibernate:
      ddl-auto: create-drop
    show-sql: true

  data:
    redis:
      host: localhost
      port: 6379

management:
  endpoints:
    web:
      exposure:
        include: health, metrics, prometheus
  metrics:
    export:
      prometheus:
        enabled: true

---
spring:
  config:
    activate:
      on-profile: problem

cache:
  ttl: 10 # 10초 (짧게 설정해서 만료 재현 용이)
  jitter: 0 # Jitter 없음

---
spring:
  config:
    activate:
      on-profile: solution-v1

cache:
  ttl: 10
  jitter: 0

---
spring:
  config:
    activate:
      on-profile: solution-v2

cache:
  ttl: 10
  jitter: 5 # 0~5초 랜덤 추가

---
spring:
  config:
    activate:
      on-profile: solution-v3

cache:
  ttl: 60
  jitter: 0
  warming:
    cron: "*/5 * * * * *" # 5초마다 갱신
    product-ids: 1, 2, 3 # 워밍 대상 상품 ID
```

### 1-4. 공통 도메인

**Product.kt**

```kotlin
@Entity
data class Product(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    val name: String,
    val price: Int,
    val stock: Int
)
```

**ProductRepository.kt**

```kotlin
interface ProductRepository : JpaRepository<Product, Long>
```

**DataInitializer.kt**

- `ApplicationRunner` 구현
- 앱 시작 시 상품 데이터 10개 삽입 (id: 1~10)
- 이미 데이터가 있으면 삽입 생략

**RedisConfig.kt**

- `RedisTemplate<String, Any>` 빈 등록
- Key serializer: StringRedisSerializer
- Value serializer: GenericJackson2JsonRedisSerializer

---

## Step 2. Problem 구현

### 목표

캐시 미스 시 아무런 보호 없이 DB를 직접 조회합니다.
동시에 수백 개의 요청이 들어오면 전부 DB를 조회하게 됩니다.

### ProductService.kt (problem)

동작 순서:

1. Redis에서 `product:{id}` 키로 조회
2. 캐시 히트 → 즉시 반환
3. 캐시 미스 → DB 조회
4. DB 조회 시 `db.query.count` 카운터 증가
5. Redis에 TTL과 함께 저장
6. 반환

주의: 락, 뮤텍스, 어떤 보호 장치도 없어야 합니다.

### ProductController.kt (problem)

```
GET /problem/products/{id}
```

### 캐시 키 규칙

```
product:{id}
예) product:1
```

---

## Step 3. Solution V1 — Mutex Lock (코드 레벨)

### 목표

캐시 미스 발생 시, 단 하나의 요청만 DB를 조회하게 합니다.
나머지 요청은 뮤텍스를 기다린 뒤 캐시에서 읽습니다.

### 동작 순서

1. Redis에서 캐시 조회
2. 캐시 히트 → 즉시 반환
3. 캐시 미스 → 해당 상품 ID에 대한 Mutex 획득 시도
4. Mutex 획득 후 캐시 재확인 (Double-checked locking)
   - 캐시에 값이 있으면 → 반환 (다른 스레드가 이미 채운 것)
   - 캐시에 값이 없으면 → DB 조회, 카운터 증가, 캐시 저장
5. Mutex 해제

### 구현 규칙

- 상품 ID별로 독립된 Mutex를 가져야 합니다 (`ConcurrentHashMap<Long, Mutex>` 사용)
- Mutex는 `kotlinx.coroutines.sync.Mutex` 를 사용합니다
- Controller 엔드포인트: `GET /solution/v1/products/{id}`

---

## Step 4. Solution V2 — TTL Jitter (설계 레벨)

### 목표

모든 상품의 캐시가 동시에 만료되지 않도록 TTL에 랜덤값을 더합니다.
Stampede 자체가 발생하지 않도록 설계를 바꿉니다.

### 동작 순서

1. Redis에서 캐시 조회
2. 캐시 히트 → 즉시 반환
3. 캐시 미스 → DB 조회, 카운터 증가
4. TTL 계산: `baseTtl + Random(0, jitter)` 초
5. 계산된 TTL로 캐시 저장
6. 반환

### 구현 규칙

- `cache.ttl`, `cache.jitter` 값은 `application.yml` 에서 주입
- `java.util.concurrent.ThreadLocalRandom` 사용
- Controller 엔드포인트: `GET /solution/v2/products/{id}`

---

## Step 5. Solution V3 — Cache Warming (인프라 레벨)

### 목표

요청이 들어오기 전에 스케줄러가 미리 캐시를 채웁니다.
캐시 미스 자체가 거의 발생하지 않는 상태를 유지합니다.

### 동작 순서 (스케줄러)

1. `cache.warming.cron` 주기로 실행
2. `cache.warming.product-ids` 목록의 상품을 DB에서 조회
3. 현재 캐시 TTL 잔여 시간 확인
4. 잔여 TTL이 기준값(예: 전체 TTL의 50%) 이하이면 캐시 갱신
5. 카운터는 증가시키지 않음 (스케줄러 조회는 계측 제외)

### 동작 순서 (요청)

1. Redis에서 캐시 조회
2. 캐시 히트 → 즉시 반환 (거의 항상 히트)
3. 캐시 미스 (드문 경우) → DB 조회, 카운터 증가, 캐시 저장

### 구현 규칙

- `@Scheduled` 로 스케줄러 구현
- 스케줄러와 일반 요청 처리는 별도 클래스로 분리
- Controller 엔드포인트: `GET /solution/v3/products/{id}`

---

## Step 6. 테스트 구현

### 단위 테스트 (각 solution 별)

**검증 항목**: 동시 요청 N개 발생 시 DB 쿼리 횟수

```
ConcurrentRequestTest:
  - given: 캐시가 비어있는 상태, 상품 ID=1
  - when: 동시 요청 100개 (CountDownLatch 사용)
  - then:
    - problem     → db.query.count >= 50 (대부분 DB 직접 조회)
    - solution-v1 → db.query.count == 1  (단 1번만 DB 조회)
    - solution-v2 → db.query.count >= 1  (Jitter는 횟수보다 분산 검증)
    - solution-v3 → db.query.count == 0  (캐시가 항상 채워져 있음)
```

TestContainers로 실제 Redis, PostgreSQL을 띄워서 테스트합니다.

### 통합 테스트

`@SpringBootTest` + `TestRestTemplate` 으로 실제 HTTP 요청을 발생시킵니다.

---

## Step 7. k6 부하 테스트

### 파일 위치

`load-test/scenario.js`

### 시나리오

**Phase 1 - 캐시 워밍업** (10초)

- VU 10개로 요청 발생 → 캐시가 채워진 상태 만들기

**Phase 2 - 대기** (캐시 TTL 만료까지 대기, 약 10초)

- VU 0

**Phase 3 - Stampede 발생** (5초)

- VU 500개로 급격히 증가
- 이 시점에 캐시가 막 만료된 상태

**Phase 4 - 안정화 확인** (10초)

- VU 100개 유지

### 측정 항목

- `http_req_duration` (p50, p95, p99)
- `http_req_failed`
- `http_reqs` (초당 요청 수)

### 실행 방법

```bash
# 결과를 CSV로 저장
k6 run --out csv=results/metrics.csv load-test/scenario.js
```

---

## Step 8. README.md 작성

실험이 완료되면 아래 수치를 채워서 README.md를 완성합니다.

| 항목         | Problem | V1 Mutex | V2 Jitter | V3 Warming |
| ------------ | ------- | -------- | --------- | ---------- |
| DB 쿼리 수   | ?       | ?        | ?         | ?          |
| p99 응답시간 | ?       | ?        | ?         | ?          |
| 에러율       | ?       | ?        | ?         | ?          |
| 구현 복잡도  | -       | 낮음     | 낮음      | 중간       |

results/ 디렉터리에 아래 파일을 저장합니다.

```
results/
├── before.png          ← k6 problem 실행 결과 스크린샷
├── after-v1.png        ← k6 solution-v1 실행 결과 스크린샷
├── after-v2.png        ← k6 solution-v2 실행 결과 스크린샷
├── after-v3.png        ← k6 solution-v3 실행 결과 스크린샷
└── metrics.csv         ← k6 raw 데이터
```
