# ConcurrencySafeCoupon 구현 플랜

## Context

선착순 쿠폰 발급 시스템 구현. 수천 명의 동시 요청을 처리하면서:
- 수량 초과 발급 없음 (정확한 재고 제어)
- 동일 사용자 중복 발급 없음
- 빠른 응답 속도

**ASSIGNMENT.md** 기반. 현재 프로젝트는 Spring Boot 메인 클래스만 존재하는 스캐폴드 상태.

---

## 아키텍처: Redis + Kafka + MySQL 3중 방어

```
POST /api/coupons/{id}/issue
    ↓
[CouponIssueService]
    ↓
Redis Lua Script (원자적: 중복 체크 + 재고 감소)  ← 동시성의 핵심
    ↓ 성공(1) / 중복(-1) / 재고없음(-2)
Kafka 발행 "coupon-issue-requests"
    ↓ 202 Accepted 즉시 응답
[CouponIssueConsumer] (비동기)
    ↓
MySQL 영구 저장 (UNIQUE constraint 최후 보루)
```

**왜 Redis Lua Script?**
- 중복 사용자 체크 + 재고 감소를 단일 원자 연산으로 처리
- 단순 INCR 대비 race window 없음

**왜 Kafka?**
- DB 쓰기(느림)를 HTTP 응답(빨라야 함)에서 분리
- 플래시세일 급증 시 버퍼 역할

---

## 구현 순서 (의존성 순서 준수)

### Phase 1 — 인프라 설정 (3개 파일)
1. `docker-compose.yml` — MySQL 8.0, Redis 7, Kafka (KRaft mode)
2. `src/main/resources/application.properties` — DB/Redis/Kafka 연결 설정
3. `build.gradle` — Jackson 의존성 추가 (`jackson-databind`, `jackson-datatype-jsr310`)

### Phase 2 — 도메인 열거형 + 베이스 (4개 파일)
4. `domain/common/BaseTimeEntity.java` — `@MappedSuperclass` + createdAt/updatedAt
5. `domain/coupon/CouponType.java` — `FIXED_AMOUNT, PERCENTAGE`
6. `domain/coupon/CouponStatus.java` — `ACTIVE, INACTIVE, EXHAUSTED, EXPIRED`
7. `domain/issuedcoupon/IssuedCouponStatus.java` — `ISSUED, USED, EXPIRED, CANCELLED`

### Phase 3 — 엔티티 (3개 파일)
8. `domain/coupon/Coupon.java`
   - `@Version Long version` (낙관적 락, DB 쓰기 경로 보호)
   - `isAvailable()`, `incrementIssuedQuantity()` 비즈니스 메서드
9. `domain/issuedcoupon/IssuedCoupon.java`
   - `UNIQUE(coupon_id, user_id)` 제약 조건
   - `static IssuedCoupon issue(Coupon, userId)` 팩토리 메서드
   - `use()` 상태 검증 메서드
10. `domain/usagehistory/CouponUsageHistory.java`
    - `static CouponUsageHistory of(IssuedCoupon, orderId, discountAmount)`

### Phase 4 — 레포지토리 (3개 파일)
11. `domain/coupon/CouponRepository.java`
    - `findByStatus(CouponStatus)`, `findByIdWithLock(@Lock PESSIMISTIC_WRITE)`
12. `domain/issuedcoupon/IssuedCouponRepository.java`
    - `findByUserId`, `findByUserIdAndStatus`, `existsByCouponIdAndUserId`, `countByCouponId`
13. `domain/usagehistory/CouponUsageHistoryRepository.java`

### Phase 5 — 인프라 (Redis + Kafka) (7개 파일)
14. `infrastructure/redis/RedisConfig.java` — `RedisTemplate`, `StringRedisTemplate` 빈
15. **`infrastructure/redis/CouponRedisRepository.java`** ← 동시성 핵심
    - Redis 키: `coupon:stock:{id}` (재고), `coupon:issued:{id}` (발급된 userId Set)
    - Lua Script: `SISMEMBER` 중복 체크 + `DECR` 재고 감소 (원자적)
    - 반환값: 1=성공, -1=중복, -2=재고없음
16. `infrastructure/kafka/KafkaConfig.java` — `coupon-issue-requests` 토픽 (파티션 3)
17. `infrastructure/kafka/CouponIssueEvent.java` — `couponId, userId, requestedAt`
18. `infrastructure/kafka/CouponIssueProducer.java` — couponId를 키로 발행 (순서 보장)
19. **`infrastructure/kafka/CouponIssueConsumer.java`** ← 멱등성 핵심
    - `findByIdWithLock` → 중복 DB 체크 → `IssuedCoupon.issue()` 저장
    - `DataIntegrityViolationException` 삼키고 로그 (UNIQUE constraint 위반)
20. `infrastructure/startup/CouponStockInitializer.java`
    - `ApplicationReadyEvent` 리스너: ACTIVE 쿠폰의 Redis 재고 DB로부터 동기화

### Phase 6 — 예외 + 공통 (6개 파일)
21. `exception/CouponNotFoundException.java` → 404
22. `exception/CouponExhaustedException.java` → 409
23. `exception/DuplicateCouponIssueException.java` → 409
24. `exception/CouponNotAvailableException.java` → 400
25. `common/ApiResponse.java` — `success(T data)`, `failure(String message)` 팩토리
26. `exception/GlobalExceptionHandler.java` — `@RestControllerAdvice`

### Phase 7 — 서비스 (3개 파일)
27. `service/CouponService.java` — 목록 조회, 재고 조회, Redis 초기화
28. **`service/CouponIssueService.java`** ← 핫 패스 오케스트레이터
    - `isAvailable()` 검증 → Redis Lua → Kafka 발행
29. `service/IssuedCouponService.java` — 사용자 쿠폰 조회, 쿠폰 사용

### Phase 8 — DTO + 컨트롤러 (5개 파일)
30. `controller/dto/CouponIssueRequest.java` — `@NotNull Long userId`
31. `controller/dto/CouponResponse.java`
32. `controller/dto/IssuedCouponResponse.java`
33. `controller/CouponController.java` — 5개 API 엔드포인트
34. `controller/CouponAdminController.java` — Redis 재고 초기화 API

### Phase 9 — 메인 클래스 수정
35. `ConcurrencySafeCouponApplication.java` — `@EnableJpaAuditing` 추가

### Phase 10 — 테스트 (3개 파일)
36. `service/CouponIssueServiceTest.java` — 단위 테스트 (Mockito)
37. `service/CouponConcurrencyTest.java` ← 동시성 정합성 증명
    - 1,000명 동시 요청 → 정확히 100명 성공
    - 동일 사용자 100번 동시 요청 → 정확히 1번 성공
    - `ExecutorService` + `CountDownLatch` + `AtomicInteger` 활용
38. `infrastructure/redis/CouponRedisRepositoryTest.java` — Lua Script 직접 테스트

---

## 핵심 파일 경로

| 역할 | 파일 경로 |
|---|---|
| 동시성 핵심 | `infrastructure/redis/CouponRedisRepository.java` |
| 멱등성 핵심 | `infrastructure/kafka/CouponIssueConsumer.java` |
| 핫 패스 | `service/CouponIssueService.java` |
| 인프라 | `docker-compose.yml` |
| 정합성 증명 | `test/.../CouponConcurrencyTest.java` |

---

## 주의사항 (잠재적 이슈)

1. **Redis 재시작 시 재고 소실**: `CouponStockInitializer`가 DB에서 재계산. docker-compose에 `appendonly yes` 설정.
2. **Kafka 재발행(at-least-once)**: Consumer의 `existsByCouponIdAndUserId` 체크 + `DataIntegrityViolationException` 처리로 멱등성 보장.
3. **쿠폰 만료 Race**: `isAvailable()` 체크 후 Redis 호출 사이 만료 가능 → Consumer에서도 재검증 추가 필요.
4. **eventual consistency**: Redis 재고는 실시간, DB `issuedQuantity`는 약간의 지연. Redis 값이 권위적 재고 기준.

---

## 검증 방법

1. `docker-compose up -d` → MySQL, Redis, Kafka 실행
2. 앱 시작 → Redis 재고 초기화 로그 확인
3. 쿠폰 DB INSERT 후 `/api/admin/coupons/{id}/init-stock` 호출
4. `CouponConcurrencyTest` 실행 → 두 시나리오 모두 통과 확인
5. `GET /api/coupons/{id}/stock` → Redis 잔여 재고 확인
