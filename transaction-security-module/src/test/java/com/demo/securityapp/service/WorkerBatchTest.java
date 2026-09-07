package com.demo.securityapp.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Timeout(5)
class WorkerBatchTest {
    @Test void shutdownCompletesRemovedQueuedFuturesForTheBatchWaiter() throws Exception {
        var occupied=new CountDownLatch(2);
        var release=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            Runnable blocked=()->{
                occupied.countDown();
                try {release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
            };
            var first=workers.submit(blocked);
            var second=workers.submit(blocked);
            assertThat(occupied.await(1,TimeUnit.SECONDS)).isTrue();
            var queued=workers.submit(()->{});
            Processor.cancelQueued(workers.shutdownNow());
            assertThat(queued.isCancelled()).isTrue();
            assertThatThrownBy(()->Processor.awaitBatch(List.of(first,second,queued)))
                .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(CancellationException.class);
        } finally {release.countDown();}
    }

    @Test void failedFirstTaskDoesNotReleaseAdmissionWhileAnotherTaskIsPending() throws Exception {
        var failed=CompletableFuture.failedFuture(new IllegalStateException("Claim unavailable"));
        var pending=new CompletableFuture<Void>();
        try(var caller=Executors.newSingleThreadExecutor()) {
            var batch=caller.submit(()->Processor.awaitBatch(List.of(failed,pending)));
            try {
                assertThatThrownBy(()->batch.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {pending.complete(null);}
            assertThatThrownBy(()->batch.get(1,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasRootCauseMessage("Claim unavailable");
        }
    }

    @Test void interruptionPreservesAdmissionUntilThePendingBatchFinishes() throws Exception {
        var pending=new CompletableFuture<Void>();
        var entered=new CountDownLatch(1);
        try(var caller=Executors.newSingleThreadExecutor()) {
            var batch=caller.submit(()->{
                Thread.currentThread().interrupt();
                entered.countDown();
                Processor.awaitBatch(List.of(pending));
                return Thread.currentThread().isInterrupted();
            });
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(()->batch.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {pending.complete(null);}
            assertThat(batch.get(1,TimeUnit.SECONDS)).isTrue();
        }
    }
}
