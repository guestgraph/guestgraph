package io.guestgraph.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.guestgraph.resolution.TenantLock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class TenantLockTest extends PostgresIntegrationTest {

  @Autowired TenantLock tenantLock;

  @Autowired PlatformTransactionManager transactionManager;

  @Test
  void requiresAnActiveTransaction() {
    assertThatThrownBy(() -> tenantLock.acquire(TENANT_A))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("transaction");
  }

  @Test
  void serializesSameTenantAndNotOtherTenants() throws Exception {
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    CountDownLatch firstHoldsLock = new CountDownLatch(1);
    Duration holdTime = Duration.ofMillis(500);

    CompletableFuture<Instant> firstReleased =
        CompletableFuture.supplyAsync(
            () ->
                tx.execute(
                    status -> {
                      tenantLock.acquire(TENANT_A);
                      firstHoldsLock.countDown();
                      sleep(holdTime);
                      return Instant.now();
                    }));

    firstHoldsLock.await();
    CompletableFuture<Instant> sameTenantAcquired =
        CompletableFuture.supplyAsync(
            () ->
                tx.execute(
                    status -> {
                      tenantLock.acquire(TENANT_A);
                      return Instant.now();
                    }));
    CompletableFuture<Instant> otherTenantAcquired =
        CompletableFuture.supplyAsync(
            () ->
                tx.execute(
                    status -> {
                      tenantLock.acquire(TENANT_B);
                      return Instant.now();
                    }));

    // Another tenant is not blocked while the first transaction still holds the lock.
    assertThat(otherTenantAcquired.get()).isBefore(firstReleased.get());
    // The same tenant only proceeds once the first transaction has committed.
    assertThat(sameTenantAcquired.get()).isAfterOrEqualTo(firstReleased.get());
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
