package modder.hub.dexeditor.activity;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks Activity-owned workers and interrupts them when the Activity is destroyed. */
final class BackgroundTaskScope {
    private final Set<Thread> workers = ConcurrentHashMap.newKeySet();
    private boolean closed;

    synchronized Thread execute(String name, Runnable work) {
        if (closed) return null;
        Thread worker = new Thread(() -> {
            try {
                work.run();
            } finally {
                workers.remove(Thread.currentThread());
            }
        }, name);
        workers.add(worker);
        worker.start();
        return worker;
    }

    synchronized void cancelAll() {
        closed = true;
        for (Thread worker : workers) worker.interrupt();
        workers.clear();
    }
}
