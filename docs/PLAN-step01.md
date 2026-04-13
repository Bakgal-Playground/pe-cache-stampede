# Step 1 — 공통 환경 구성

## 목표

Cache Stampede 실험 전체의 기반이 되는 공통 환경을 구성합니다.

## 생성 파일

| 파일 | 내용 |
|------|------|
| `build.gradle.kts` | 의존성 및 플러그인 |
| `settings.gradle.kts` | 프로젝트 이름 |
| `Dockerfile` | 멀티스테이지 빌드 |
| `docker-compose.yml` | postgres / redis / app (리소스 제한 + 헬스체크) |
| `src/main/resources/application.yml` | 4개 프로파일 설정 |
| `common/domain/Product.kt` | JPA 엔티티 |
| `common/repository/ProductRepository.kt` | JPA Repository |
| `common/config/RedisConfig.kt` | RedisTemplate 빈 등록 |
| `common/config/DataInitializer.kt` | 앱 시작 시 상품 10개 초기 삽입 |
| `CacheStampedeApplication.kt` | Spring Boot 진입점 |

## 프로파일별 캐시 설정

| Profile | `cache.ttl` | `cache.jitter` | `cache.warming` |
|---------|------------|----------------|-----------------|
| problem | 10s | 0 | - |
| solution-v1 | 10s | 0 | - |
| solution-v2 | 10s | 5s | - |
| solution-v3 | 60s | 0 | cron: `*/5 * * * * *`, ids: 1,2,3 |

## 검증

```bash
./gradlew build -x test
docker compose up -d postgres redis
./gradlew bootRun --args='--spring.profiles.active=problem'
curl http://localhost:8080/actuator/health
```
