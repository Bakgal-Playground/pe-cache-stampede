# 진행 상태

| Step | 내용 | 브랜치 | 상태 |
|------|------|--------|------|
| Step 1 | 공통 환경 구성 | main | ✅ 완료 |
| Step 2 | Problem 구현 | step/02-problem | ⬜ 대기 |
| Step 3 | Solution V1 — Mutex Lock | step/03-solution-v1 | ⬜ 대기 |
| Step 4 | Solution V2 — TTL Jitter | step/04-solution-v2 | ⬜ 대기 |
| Step 5 | Solution V3 — Cache Warming | step/05-solution-v3 | ⬜ 대기 |
| Step 6 | 테스트 구현 | step/06-tests | ⬜ 대기 |
| Step 7 | k6 부하 테스트 | step/07-load-test | ⬜ 대기 |
| Step 8 | README 결과 채우기 | step/08-results | ⬜ 대기 |

## 다음 단계

**Step 2: Problem 구현**

```bash
git checkout -b step/02-problem
```

- `problem/ProductController.kt` — `GET /problem/products/{id}`
- `problem/ProductService.kt` — 보호 장치 없는 Cache-Aside (락/뮤텍스 금지)
- 스펙: `docs/EXPERIMENT.md` Step 2
