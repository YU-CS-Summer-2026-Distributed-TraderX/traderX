package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;

/**
 * Bounded handoff from the matcher output ring to allocation-heavy external edges.
 *
 * The output-ring handler only copies primitive event state into preallocated slots and
 * applies backpressure when the edge queue is full. NATS JSON publication and JPA
 * projection run on the worker thread behind this boundary.
 */
public final class OutputExternalEdgeHandler implements EventHandler<OutputEvent>, AutoCloseable, Runnable {
    private static final long CLOSE_JOIN_MILLIS = 5_000L;

    private final EventQueue free;
    private final EventQueue ready;
    private final EventHandler<OutputEvent>[] delegates;
    private final Thread worker;
    private volatile boolean running = true;
    private volatile long submittedSeq = -1;
    private volatile long processedSeq = -1;

    @SafeVarargs
    public OutputExternalEdgeHandler(int capacity, EventHandler<OutputEvent>... delegates) {
        int normalizedCapacity = Math.max(1, capacity);
        this.free = new EventQueue(normalizedCapacity);
        this.ready = new EventQueue(normalizedCapacity);
        this.delegates = delegates;
        for (int i = 0; i < normalizedCapacity; i++) {
            free.add(OutputEvent.newInstance());
        }
        this.worker = new Thread(this, "lmax-output-external-edge");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public void onEvent(OutputEvent e, long sequence, boolean endOfBatch) throws InterruptedException {
        OutputEvent copy = free.take();
        copy.copyFrom(e);
        ready.put(copy);
        submittedSeq = sequence;
    }

    @Override
    public void run() {
        while (running || !ready.isEmpty()) {
            OutputEvent e = null;
            try {
                e = ready.take();
                for (EventHandler<OutputEvent> delegate : delegates) {
                    delegate.onEvent(e, processedSeq + 1, true);
                }
                processedSeq++;
            } catch (InterruptedException ex) {
                if (!running && ready.isEmpty()) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (Exception ex) {
                // Delegates already record their own failure counters where applicable.
            } finally {
                if (e != null) {
                    returnToFree(e);
                }
            }
        }
    }

    private void returnToFree(OutputEvent e) {
        while (running) {
            try {
                free.put(e);
                return;
            } catch (InterruptedException ex) {
                if (!running) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        free.offer(e);
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
        try {
            worker.join(CLOSE_JOIN_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public long submittedSeq() {
        return submittedSeq;
    }

    public long processedSeq() {
        return processedSeq;
    }

    public int pendingEvents() {
        return ready.size();
    }

    public int remainingCapacity() {
        return ready.remainingCapacity();
    }

    private static final class EventQueue {
        private final OutputEvent[] events;
        private int head;
        private int tail;
        private int size;

        private EventQueue(int capacity) {
            this.events = new OutputEvent[capacity];
        }

        private synchronized void add(OutputEvent event) {
            events[tail] = event;
            tail = next(tail);
            size++;
        }

        private synchronized void put(OutputEvent event) throws InterruptedException {
            while (size == events.length) {
                wait();
            }
            events[tail] = event;
            tail = next(tail);
            size++;
            notifyAll();
        }

        private synchronized OutputEvent take() throws InterruptedException {
            while (size == 0) {
                wait();
            }
            OutputEvent event = events[head];
            events[head] = null;
            head = next(head);
            size--;
            notifyAll();
            return event;
        }

        private synchronized boolean offer(OutputEvent event) {
            if (size == events.length) {
                return false;
            }
            events[tail] = event;
            tail = next(tail);
            size++;
            notifyAll();
            return true;
        }

        private synchronized boolean isEmpty() {
            return size == 0;
        }

        private synchronized int size() {
            return size;
        }

        private synchronized int remainingCapacity() {
            return events.length - size;
        }

        private int next(int index) {
            int next = index + 1;
            return next == events.length ? 0 : next;
        }
    }
}
