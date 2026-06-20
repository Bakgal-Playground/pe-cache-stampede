import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL  = __ENV.BASE_URL  || 'http://localhost:8080';
const PROFILE   = __ENV.PROFILE   || 'problem';
const VUS       = parseInt(__ENV.VUS       || '500');
const TTL_WAIT  = parseInt(__ENV.TTL_WAIT  || '11');

export const options = {
  scenarios: {
    stampede: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: VUS,
    },
  },
  thresholds: {
    http_req_failed:   ['rate<0.05'],
    http_req_duration: ['p(99)<3000'],
  },
};

export function setup() {
  const res = http.get(`${BASE_URL}/${PROFILE}/products/1`);
  check(res, { 'cache warmed': (r) => r.status === 200 });
  console.log(`[${PROFILE}] VUs=${VUS} | Cache warmed. TTL 만료 대기 중 (${TTL_WAIT}s)...`);
  sleep(TTL_WAIT);
  console.log(`[${PROFILE}] TTL 만료. Stampede 시작...`);
}

export default function () {
  const res = http.get(`${BASE_URL}/${PROFILE}/products/1`);
  check(res, {
    'status 200': (r) => r.status === 200,
  });
}
