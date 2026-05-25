
## 프로젝트 개요

재고 차감 기능을 예제로 동시성 문제를 학습한 프로젝트

여러 사용자가 동시에 같은 상품의 재고를 차감할 때 발생하는 Race Condition 재현  
이를 해결하기 위한 여러 락 방식 비교

단순 재고 차감 기능 구현보다 동시성 문제가 발생하는 이유와 각 해결 방식의 차이를 직접 확인하는 데 목적

---

## 기술 스택

### Backend

- Java 11
- Spring Boot 2.7.16
- Spring Web
- Spring Data JPA
- Redisson

### Database

- MySQL

### Test

- JUnit 5
- Spring Boot Test
- CountDownLatch
- ExecutorService

---

## 핵심 학습 주제

- Race Condition
- Java synchronized
- Database Pessimistic Lock
- Database Optimistic Lock
- Redis Distributed Lock
- Redisson RLock
- 트랜잭션 전파 옵션
- 동시성 테스트 작성

---

## 도메인 모델

재고를 나타내는 `Stock` 엔티티

```java
@Entity
public class Stock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long productId;

    private Long quantity;

    @Version
    private Long version;

    public Stock() {
    }

    public Stock(Long productId, Long quantity) {
        this.productId = productId;
        this.quantity = quantity;
    }

    public Long getQuantity() {
        return quantity;
    }

    public void decrease(Long quantity) {
        if (this.quantity - quantity < 0) {
            throw new RuntimeException("재고는 0개 미만이 될 수 없습니다.");
        }

        this.quantity -= quantity;
    }
}
```

`quantity`를 차감하는 단순한 로직을 통해 동시성 문제 재현  
`@Version` 필드는 Optimistic Lock 적용을 위한 필드

---

## 문제 상황

재고가 100개인 상품에 100개의 요청이 동시에 들어오는 상황 가정  
각 요청은 재고를 1개씩 차감

정상적인 결과는 다음과 같음

```text
초기 재고: 100
동시 요청 수: 100
요청당 차감 수량: 1
기대 재고: 0
```

별도의 동시성 제어가 없다면 여러 스레드가 같은 재고 값을 동시에 읽고 수정  
이 경우 최종 재고가 0이 되지 않는 문제 발생 가능

---

## 동시성 테스트

```java
@Test
void test2() throws InterruptedException {
    int threadCount = 100;
    ExecutorService executorService = Executors.newFixedThreadPool(32);
    CountDownLatch latch = new CountDownLatch(threadCount);

    for (int i = 0; i < threadCount; i++) {
        executorService.submit(() -> {
            try {
                stockService.decrease(1L, 1L);
            } finally {
                latch.countDown();
            }
        });
    }

    latch.await();

    Stock stock = stockRepository.findById(1L).orElseThrow();

    assertThat(stock.getQuantity()).isEqualTo(0);
}
```

100개의 요청을 동시에 실행  
모든 요청이 끝난 뒤 최종 재고 검증  
락 방식에 따른 테스트 성공 여부 비교

---

## 해결 방법 1. synchronized

`synchronized`를 사용해 하나의 JVM 안에서 한 번에 하나의 스레드만 특정 로직에 접근하도록 제한

### 장점

- 구현이 단순함
- 별도 인프라 불필요
- 단일 서버 환경에서 동시성 제어 가능

### 단점

- 하나의 JVM 안에서만 동작
- 서버가 여러 대일 경우 동시성 제어 불가
- 실제 분산 환경에는 부적합

`synchronized`는 학습용으로는 유용하지만, 운영 환경에서 서버가 여러 대로 확장될 수 있다면 한계 존재

---

## 해결 방법 2. Pessimistic Lock

Pessimistic Lock은 데이터를 읽는 시점에 DB Lock을 획득하는 방식  
다른 트랜잭션이 같은 데이터를 동시에 수정하지 못하도록 제어

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select s from Stock s where s.id = :id")
Stock findByIdWithPessimisticLock(Long id);
```

### 장점

- 충돌이 많은 상황에서 안정적
- 강한 데이터 정합성 보장
- 재고 차감처럼 동시에 같은 데이터를 수정하는 기능에 적합

### 단점

- DB Lock 사용으로 DB 부하 증가 가능
- Lock 대기 시간 증가 가능
- Deadlock 가능성 고려 필요

---

## 해결 방법 3. Optimistic Lock

Optimistic Lock은 데이터를 수정할 때 version 값을 비교하는 방식  
다른 트랜잭션이 먼저 데이터를 수정했다면 예외 발생  
실패한 요청은 재시도 필요

```java
@Version
private Long version;
```

```java
@Lock(LockModeType.OPTIMISTIC)
@Query("select s from Stock s where s.id = :id")
Stock findByIdWithOptimisticLock(Long id);
```

재시도 로직은 Facade 계층에서 처리

```java
public void decrease(Long id, Long quantity) throws InterruptedException {
    while (true) {
        try {
            optimisticLockStockService.decrease(id, quantity);
            break;
        } catch (Exception e) {
            Thread.sleep(50);
        }
    }
}
```

### 장점

- DB Lock을 오래 잡지 않음
- 충돌이 적은 상황에서 효율적
- version 값으로 충돌 감지 가능

### 단점

- 충돌이 많으면 재시도 증가
- 재시도 로직 직접 구현 필요
- 재고 차감처럼 충돌이 잦은 기능에서는 비효율적일 수 있음

---

## 해결 방법 4. Redis Distributed Lock

Redis 기반 분산 락은 여러 애플리케이션 인스턴스가 같은 Redis를 바라보며 Lock을 획득하는 방식

이 프로젝트에서는 Redisson의 `RLock` 사용

```java
public void decrease(Long id, Long quantity) {
    RLock lock = redissonClient.getLock(id.toString());

    try {
        boolean available = lock.tryLock(10, 1, TimeUnit.SECONDS);

        if (!available) {
            System.out.println("lock 획득 실패");
            return;
        }

        stockService.decrease(id, quantity);
    } catch (InterruptedException e) {
        throw new RuntimeException(e);
    } finally {
        lock.unlock();
    }
}
```

### 장점

- 멀티 인스턴스 환경에서 사용 가능
- DB Lock 사용 감소
- 특정 리소스 단위로 락 제어 가능

### 단점

- Redis 인프라 필요
- lease time 설정 주의 필요
- 락 해제 시점과 트랜잭션 커밋 시점 고려 필요

---

## 트랜잭션 전파 옵션

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void decrease(Long id, Long quantity) {
    Stock stock = stockRepository.findById(id).orElseThrow();
    stock.decrease(quantity);

    stockRepository.saveAndFlush(stock);
}
```

락을 획득하는 Facade와 실제 재고를 차감하는 Service의 트랜잭션 경계 분리  
재시도 로직이나 Redis Lock 사용 시 트랜잭션 범위를 명확히 하기 위한 구성

---

## 방식별 비교

| 방식 | 장점 | 단점 | 분산 환경 적합성 |
|---|---|---|---|
| synchronized | 구현이 단순함 | 단일 JVM에서만 유효함 | 낮음 |
| Pessimistic Lock | 정합성 보장이 강함 | DB 부하가 커질 수 있음 | 보통 |
| Optimistic Lock | 충돌이 적을 때 효율적임 | 재시도 로직이 필요함 | 보통 |
| Redis Lock | 멀티 인스턴스 대응 가능 | Redis 인프라가 필요함 | 높음 |

---

## 학습한 내용

- Race Condition이 발생하는 이유
- CountDownLatch를 활용한 동시 요청 테스트
- synchronized의 한계
- Pessimistic Lock과 Optimistic Lock의 차이
- Redis 기반 분산 락 적용 방법
- Redisson RLock 사용법
- 트랜잭션 경계와 락 해제 시점의 중요성

---

## 회고

동시성 문제는 일반적인 단위 테스트만으로 발견하기 어려운 영역  
재고 차감처럼 같은 데이터를 여러 요청이 동시에 수정하는 기능은 의도적으로 동시 요청을 발생시켜 검증 필요

이 프로젝트를 통해 단일 서버 환경과 멀티 인스턴스 환경에서 사용할 수 있는 동시성 제어 방식이 다르다는 점 확인  
충돌 빈도, DB 부하, 재시도 가능 여부에 따라 적절한 락 전략이 달라진다는 점 학습

---
