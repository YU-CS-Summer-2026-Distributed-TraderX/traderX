package finos.traderx.ordermatcher.cluster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** FR-OD01..03: actual production onOwner/add/get and queued task.run; no cluster or fake lifecycle. */
@Timeout(15)
class OwnerTaskDeadlineTest {
    @Test void timeoutRetiresQueuedCallableBeforeLateDrain() throws Exception {
        var mutations = new AtomicInteger();
        try (var f = new Fixture(false)) {
            f.call(() -> mutations.incrementAndGet(), 0, false);
            f.enqueued(); f.failed(TimeoutException.class);
            int queuedAtReturn = f.queue.size();
            f.drain(); f.queue.captured.get().run();
            System.out.println("TIMEOUT queuedAtReturn=" + queuedAtReturn + " lateMutations=" + mutations.get());
            assertEquals(0, mutations.get(), "a timed-out unstarted callable must never mutate on late drain");
            assertEquals(0, queuedAtReturn, "retired work removed without waiting for owner drain");
            assertTrue(f.queue.captured.get().isCancelled());
        }
    }

    @Test void interruptedWaiterRetiresQueuedCallableBeforeLateDrain() throws Exception {
        var mutations = new AtomicInteger();
        try (var f = new Fixture(false)) {
            f.call(() -> mutations.incrementAndGet(), 10_000, false);
            f.enqueued(); f.caller.interrupt(); f.failed(InterruptedException.class);
            int queuedAtReturn = f.queue.size();
            f.drain(); f.queue.captured.get().run();
            System.out.println("INTERRUPTED queuedAtReturn=" + queuedAtReturn + " lateMutations=" + mutations.get());
            assertEquals(0, mutations.get(), "interrupted unstarted caller must not mutate after return");
            assertEquals(0, queuedAtReturn);
            assertTrue(f.queue.captured.get().isCancelled());
            assertFalse(f.callerInterrupted.get(), "FutureTask.get's original interrupt clearing retained");
        }
    }

    @Test void previouslyInterruptedCallerAlsoRetiresQueuedWork() throws Exception {
        var mutations = new AtomicInteger();
        try (var f = new Fixture(false)) {
            f.call(() -> mutations.incrementAndGet(), 10_000, true);
            f.enqueued(); f.failed(InterruptedException.class);
            f.drain(); f.queue.captured.get().run();
            assertEquals(0, mutations.get());
            assertEquals(0, f.queue.size());
            assertFalse(f.callerInterrupted.get());
        }
    }

    @Test void dequeuedButUnstartedTaskCannotRunAfterTimeoutWins() throws Exception {
        var mutations = new AtomicInteger();
        try (var f = new Fixture(true)) {
            f.call(() -> mutations.incrementAndGet(), 0, false);
            f.enqueued();
            var held = f.queue.poll();
            assertSame(f.queue.captured.get(), held, "owner has dequeued actual production task");
            f.queue.releaseAdd.countDown(); f.failed(TimeoutException.class);
            held.run(); held.run();
            assertEquals(0, mutations.get(), "queue removal alone cannot protect a task already polled");
            assertTrue(held.isCancelled());
        }
    }

    @Test void successfulQueuedCallableReturnsItsValueExactlyOnce() throws Exception {
        var mutations = new AtomicInteger();
        try (var f = new Fixture(false)) {
            f.call(() -> { mutations.incrementAndGet(); return 731; }, 10_000, false);
            f.enqueued(); var task = f.queue.poll(); assertNotNull(task);
            task.run(); task.run(); f.returned();
            assertNull(f.failure.get()); assertEquals(731, f.value.get());
            assertEquals(1, mutations.get()); assertEquals(731, task.get());
            assertFalse(task.isCancelled());
        }
    }

    @Test void successfulNullResultRemainsNull() throws Exception {
        try (var f = new Fixture(false)) {
            f.call(() -> null, 10_000, false); f.enqueued(); f.drain(); f.returned();
            assertNull(f.failure.get()); assertNull(f.value.get());
            assertTrue(f.queue.captured.get().isDone()); assertFalse(f.queue.captured.get().isCancelled());
        }
    }

    @Test void callableExceptionRemainsExecutionExceptionWithOriginalCause() throws Exception {
        var cause = new IllegalStateException("callable failure");
        try (var f = new Fixture(false)) {
            f.call(() -> { throw cause; }, 10_000, false); f.enqueued(); f.drain(); f.failed(ExecutionException.class);
            assertSame(cause, f.failure.get().getCause());
            assertFalse(f.queue.captured.get().isCancelled());
        }
    }

    @Test void callableInterruptedExceptionIsNotWaiterInterruption() throws Exception {
        var cause = new InterruptedException("inside callable");
        try (var f = new Fixture(false)) {
            f.call(() -> { throw cause; }, 10_000, false); f.enqueued(); f.drain(); f.failed(ExecutionException.class);
            assertSame(cause, f.failure.get().getCause());
            assertFalse(f.queue.captured.get().isCancelled());
        }
    }

    @Test void startedBeforeDeadlineRetainsExecutionAndFutureResult() throws Exception {
        startedThenTerminated(false);
    }

    @Test void interruptedWaiterDoesNotCancelOrInterruptStartedCallable() throws Exception {
        startedThenTerminated(true);
    }

    private void startedThenTerminated(boolean interruptWaiter) throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var mutations = new AtomicInteger();
        var ownerInterrupted = new AtomicReference<Boolean>();
        try (var f = new Fixture(true)) {
            f.releaseOnClose.add(finish);
            f.call(() -> {
                entered.countDown();
                assertTrue(finish.await(5, TimeUnit.SECONDS), "bounded callable fixture released");
                ownerInterrupted.set(Thread.currentThread().isInterrupted());
                mutations.incrementAndGet(); return 731;
            }, interruptWaiter ? 10_000 : 0, false);
            f.enqueued(); var held = f.queue.poll(); assertNotNull(held);
            var owner = f.owner(held);
            assertTrue(entered.await(3, TimeUnit.SECONDS), "actual callable started before caller resumes");
            f.queue.releaseAdd.countDown();
            if (interruptWaiter) f.caller.interrupt();
            f.failed(interruptWaiter ? InterruptedException.class : TimeoutException.class);
            // No automatic retry, definitive rejection, interruption or rollback after start.
            assertFalse(held.isCancelled(), "cancel(false) must not invalidate already-started execution");
            assertFalse(held.isDone(), "owner is still running, outcome remains uncertain to former waiter");
            assertFalse(owner.isInterrupted());
            finish.countDown(); owner.join(3000); assertFalse(owner.isAlive());
            held.run();
            assertEquals(1, mutations.get()); assertEquals(731, held.get());
            assertEquals(Boolean.FALSE, ownerInterrupted.get());
            System.out.println("STARTED " + (interruptWaiter ? "interrupted" : "deadline")
                + " waiter result unknown; original future eventually returns731, mutations1");
        }
    }

    @Test void canceledUnstartedWorkCannotBlockFollowingSuccessfulCallable() throws Exception {
        var stale = new AtomicInteger(); var next = new AtomicInteger();
        try (var f = new Fixture(false)) {
            f.call(() -> stale.incrementAndGet(), 0, false); f.enqueued(); f.failed(TimeoutException.class);
            f.drain(); f.queue.captured.get().run(); assertEquals(0, stale.get());
            f.call(() -> next.incrementAndGet(), 10_000, false); f.enqueued(); f.drain(); f.returned();
            assertEquals(1, next.get()); assertNull(f.failure.get());
        }
    }

    @Test void plainFutureCancelFalseCanSucceedWhileCallableKeepsRunning() throws Exception {
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1); var mutations = new AtomicInteger();
        var task = new FutureTask<>(() -> {
            entered.countDown(); assertTrue(finish.await(3, TimeUnit.SECONDS)); return mutations.incrementAndGet();
        });
        var owner = new Thread(task, "jdk-future-control"); owner.start();
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(task.cancel(false), "JDK cancel(false) success does not prove never-started");
            assertTrue(task.isCancelled()); assertFalse(owner.isInterrupted());
        } finally { finish.countDown(); owner.join(3000); assertFalse(owner.isAlive()); }
        assertEquals(1, mutations.get(), "the running callable still mutated despite successful cancellation");
        assertThrows(CancellationException.class, task::get);
    }

    /** Only instruments enqueue visibility and holds add return to force race ordering.
     * Uses the production backing queue type and actual future created by onOwner. */
    private static final class CapturingQueue extends LinkedBlockingQueue<FutureTask<?>> {
        volatile CountDownLatch added = new CountDownLatch(1);
        final CountDownLatch releaseAdd = new CountDownLatch(1);
        final AtomicReference<FutureTask<?>> captured = new AtomicReference<>();
        final boolean pause;
        CapturingQueue(boolean pause) { this.pause = pause; }
        @Override public boolean add(FutureTask<?> task) {
            boolean result = super.add(task); captured.set(task); added.countDown();
            if (pause) {
                boolean interrupted = false;
                for (;;) {
                    try { assertTrue(releaseAdd.await(5, TimeUnit.SECONDS)); break; }
                    catch (InterruptedException ex) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
            return result;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ClusterGatewayMain gateway = new ClusterGatewayMain();
        final CapturingQueue queue;
        final AtomicReference<Object> value = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicReference<Boolean> callerInterrupted = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        final List<Thread> owners = new ArrayList<>();
        final List<CountDownLatch> releaseOnClose = new ArrayList<>();
        Thread caller;
        Fixture(boolean pause) throws Exception {
            queue = new CapturingQueue(pause); field("tasks").set(gateway, queue);
        }
        void call(Callable<?> callable, long timeout, boolean preInterrupted) throws Exception {
            assertTrue(caller == null || !caller.isAlive(), "only one waiter fixture active");
            done = new CountDownLatch(1); queue.added = new CountDownLatch(1); queue.captured.set(null);
            value.set(null); failure.set(null); callerInterrupted.set(null);
            Method method = ClusterGatewayMain.class.getDeclaredMethod("onOwner", Callable.class, long.class);
            method.setAccessible(true);
            caller = new Thread(() -> {
                if (preInterrupted) Thread.currentThread().interrupt();
                try { value.set(method.invoke(gateway, callable, timeout)); }
                catch (InvocationTargetException ex) { failure.set(ex.getCause()); }
                catch (Throwable ex) { failure.set(ex); }
                finally { callerInterrupted.set(Thread.currentThread().isInterrupted()); done.countDown(); }
            }, "owner-waiter-fixture"); caller.start();
        }
        void enqueued() throws Exception {
            assertTrue(queue.added.await(3, TimeUnit.SECONDS), "production onOwner enqueued a nonempty task");
            assertNotNull(queue.captured.get());
        }
        void returned() throws Exception {
            assertTrue(done.await(3, TimeUnit.SECONDS), "caller termination observed before late drain");
            caller.join(3000); assertFalse(caller.isAlive());
        }
        void failed(Class<? extends Throwable> type) throws Exception {
            returned(); assertInstanceOf(type, failure.get());
        }
        void drain() { FutureTask<?> task; while ((task = queue.poll()) != null) task.run(); }
        Thread owner(FutureTask<?> task) {
            var owner = new Thread(task, "controlled-owner-fixture"); owners.add(owner); owner.start(); return owner;
        }
        @Override public void close() throws Exception {
            queue.releaseAdd.countDown(); releaseOnClose.forEach(CountDownLatch::countDown);
            if (caller != null) { caller.interrupt(); caller.join(3000); assertFalse(caller.isAlive()); }
            for (Thread owner : owners) { owner.join(3000); assertFalse(owner.isAlive(), "owner never interrupted by cleanup"); }
            ((HttpClient)field("readModelClient").get(gateway)).close();
        }
    }

    private static Field field(String name) throws Exception {
        var f = ClusterGatewayMain.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
}
