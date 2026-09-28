package modder.hub.dexeditor.activity;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BackgroundTaskScopeTest {
    @Test
    public void closingScopeInterruptsCurrentWorkersAndRejectsNewWork() throws Exception {
        BackgroundTaskScope scope = new BackgroundTaskScope();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        scope.execute("scope-test", () -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                interrupted.countDown();
            }
        });

        assertTrue(started.await(2, TimeUnit.SECONDS));
        scope.cancelAll();
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));

        AtomicBoolean lateWorkRan = new AtomicBoolean();
        CountDownLatch lateWork = new CountDownLatch(1);
        scope.execute("scope-test-late", () -> {
            lateWorkRan.set(true);
            lateWork.countDown();
        });
        assertFalse(lateWork.await(100, TimeUnit.MILLISECONDS));
        assertFalse(lateWorkRan.get());
    }
}
