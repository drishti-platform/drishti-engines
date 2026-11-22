/*
 * Drishti Engines · first-party engines by contract.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.engines.python;

import com.ash.engine.RequestClass;
import com.ash.engines.python.PoolException.Code;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * THE process pool: one per host, for every Python the host runs (EN-54). It replaces Drishti's three pools (the {@code python} pool,
 * the {@code pivot-fn} pool and the reactive compute workers) with one set of worker processes, one fair queue and one set of limits.
 *
 * <ul>
 *   <li><b>Lifecycle:</b> workers start on demand (or {@code workersMin} at {@link #start}), are replaced after {@code recycleAfter}
 *       requests or any error, and stop after {@code idleStop} idle (down to {@code workersMin}). A crash is {@code ENG-7001} for the
 *       request in flight; the next request gets a fresh worker; the engine's switch stays on.</li>
 *   <li><b>Admission:</b> a fair queue by class (interactive, event, batch with its share) and by user (round robin, at most
 *       {@code maxInflightPerUser} at once); a full queue answers {@code ENG-5001} at once, never grows without end.</li>
 *   <li><b>Cancel and time:</b> the wall-time budget and cancellation are enforced from outside: in fork mode only the request's child
 *       is killed and the worker stays; in warm mode the worker goes.</li>
 *   <li><b>Limits:</b> memory and CPU per request, the size of a result, from {@link PoolSettings}; the sandbox is the worker's.</li>
 * </ul>
 *
 * Which engines run is the pool's list of {@code module:Class} entries (the {@code python} engine's {@code engine_python.engine:PythonEngine}
 * and the worker engines of packs): a request names one.
 */
public final class PythonPool implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger("engine.python.pool");
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    /** A waiting or running request. */
    private static final class Job implements FairQueue.Item {
        final PoolRequest request;
        final long deadlineNanos;
        final CompletableFuture<PoolResult> future = new CompletableFuture<>();
        volatile boolean cancelled;
        volatile boolean running;

        Job(PoolRequest request, long deadlineNanos) {
            this.request = request;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public RequestClass cls() {
            return request.cls();
        }

        @Override
        public String user() {
            return request.user();
        }
    }

    /** What Admin shows. */
    public record Stats(int workers, int busy, int idle, int waiting, long served, int restarts) {}

    private final PoolSettings settings;
    private final Path work;
    private final Path scratchRoot;
    private final String node;
    private final List<String> engines;
    private final ClassLoader resources;
    private final ReentrantLock lock = new ReentrantLock();
    private final FairQueue<Job> queue;
    private final Deque<WorkerProcess> idle = new ArrayDeque<>();
    private final List<WorkerProcess> all = new ArrayList<>();
    private final AtomicInteger numbering = new AtomicInteger();
    private final AtomicInteger restarts = new AtomicInteger();
    private volatile long served;
    private int busy;
    private volatile boolean closed;
    private volatile boolean started;
    private volatile boolean suspended;
    private Path sdk;
    private ExecutorService runners;
    private ScheduledExecutorService reaper;

    /**
     * @param settings the pool's settings
     * @param work the pool's own folder (empty at first use; the only place it writes): the package, the scratch folders, temp files
     * @param node this node's name in worker ids and logs
     * @param engines the worker engines the pool serves, {@code module:Class} each
     * @param resources where the worker package's resources are read from (the python engine's JAR loader)
     */
    public PythonPool(PoolSettings settings, Path work, String node, List<String> engines, ClassLoader resources) {
        if (engines.isEmpty()) {
            throw new IllegalArgumentException("the pool serves at least one engine (module:Class)");
        }
        for (String e : engines) {
            if (!e.matches("[A-Za-z_][A-Za-z0-9_.]*:[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("'" + e + "' is not module:Class");
            }
        }
        this.settings = settings;
        this.work = work.toAbsolutePath().normalize();
        this.scratchRoot = this.work.resolve("scratch");
        this.node = node;
        this.engines = List.copyOf(engines);
        this.resources = resources;
        this.queue = new FairQueue<>(settings.queueInteractive(), settings.queueEvent(), settings.queueBatch(), settings.maxInflightPerUser(),
                settings.batchSlots());
    }

    public PoolSettings settings() {
        return settings;
    }

    public Path scratchRoot() {
        return scratchRoot;
    }

    /** Extracts the worker package, sweeps the folders a crash left, and keeps {@code workersMin} workers warm. */
    public void start() {
        lock.lock();
        try {
            if (started) {
                return;
            }
            try {
                Folders.privateFolder(work);
                Folders.privateFolder(work.resolve("tmp"));
            } catch (java.io.IOException e) {
                throw new PoolException(Code.UNAVAILABLE, "the pool's folder " + work + " could not be made: " + e.getMessage());
            }
            Scratch.sweep(scratchRoot);
            sdk = Sdk.install(work.resolve("sdk"), resources);
            runners = Executors.newThreadPerTaskExecutor(Thread.ofPlatform().daemon().name("engine-python-run-", 0).factory());
            reaper = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("engine-python-reaper").factory());
            reaper.scheduleWithFixedDelay(this::reap, 15, 15, TimeUnit.SECONDS);
            started = true;
        } finally {
            lock.unlock();
        }
        for (int i = 0; i < settings.workersMin(); i++) {
            WorkerProcess w = launch();
            lock.lock();
            try {
                idle.addLast(w);
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Runs one request and waits for its result. Waits behind other requests fairly; throws {@link PoolException} for every failure
     * (busy, timeout, cancelled, crashed, limit, the code's own refusal, a worker that could not start).
     */
    public PoolResult submit(PoolRequest r) {
        if (closed || !started) {
            throw new PoolException(Code.OFF, "the python pool is not running").forRequest(r.id());
        }
        if (suspended) {
            throw new PoolException(Code.UNAVAILABLE, "the python engine is suspended: it takes no new work until it is resumed").forRequest(r.id());
        }
        if (!engines.contains(r.engine())) {
            throw new PoolException(Code.REFUSED, "this pool has no engine " + r.engine() + " (engines: " + engines + ")").forRequest(r.id());
        }
        Duration budget = settings.budget(r.cls() == RequestClass.BATCH);
        long deadline = System.nanoTime() + budget.toNanos();
        if (r.deadline() != null) {
            long own = Duration.between(Instant.now(), r.deadline()).toNanos();
            deadline = Math.min(deadline, System.nanoTime() + Math.max(own, 0));
        }
        Job job = new Job(r, deadline);
        lock.lock();
        try {
            if (!queue.offer(job)) {
                throw new PoolException(Code.BUSY, "the python pool is answering as many " + r.cls().name().toLowerCase() + " requests as it queues ("
                        + (r.cls() == RequestClass.INTERACTIVE ? settings.queueInteractive() : r.cls() == RequestClass.EVENT ? settings.queueEvent()
                        : settings.queueBatch()) + "); ask again shortly").forRequest(r.id());
            }
            dispatch();
        } finally {
            lock.unlock();
        }
        try {
            while (true) {
                try {
                    return job.future.get(50, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException wait) {
                    long now = System.nanoTime();
                    boolean over = now >= job.deadlineNanos;
                    if (!job.running && (over || r.cancelled().getAsBoolean())) {
                        lock.lock();
                        try {
                            if (queue.remove(job)) {
                                throw over ? new PoolException(Code.TIMEOUT, "the request waited past its time budget and was not run", r.id(), null)
                                        : new PoolException(Code.CANCELLED, "the request was cancelled before it ran", r.id(), null);
                            }
                        } finally {
                            lock.unlock();
                        }
                    }
                    if (r.cancelled().getAsBoolean()) {
                        job.cancelled = true;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            job.cancelled = true;
            throw new PoolException(Code.CANCELLED, "interrupted", r.id(), e);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof PoolException p) {
                throw p.forRequest(r.id());
            }
            throw new PoolException(Code.FAILED, "the request failed (request " + r.id() + ")", r.id(), c);
        }
    }

    /** Starts every request the free capacity and the fair order allow. Holds the lock. */
    private void dispatch() {
        while (busy < settings.workersMax() && !closed) {
            Job j = queue.next();
            if (j == null) {
                return;
            }
            busy++;
            j.running = true;
            runners.execute(() -> run(j));
        }
    }

    private void run(Job j) {
        WorkerProcess w = null;
        boolean keep = false;
        try {
            w = acquire();
            Map<String, Object> msg = message(j.request);
            WorkerProcess.Outcome o = w.run(msg, j.request.id(), j.deadlineNanos, () -> j.cancelled || j.request.cancelled().getAsBoolean(),
                    j.request.progress());
            keep = o.reusable() && w.served() < settings.recycleAfter();
            if (o.error() != null) {
                j.future.completeExceptionally(o.error());
            } else {
                j.future.complete(PoolResult.of(o.result()));
            }
        } catch (PoolException e) {
            j.future.completeExceptionally(e);
        } catch (RuntimeException e) {
            j.future.completeExceptionally(new PoolException(Code.FAILED, "the request failed (request " + j.request.id() + ")", j.request.id(), e));
        } finally {
            if (w != null) {
                release(w, keep);
            }
            lock.lock();
            try {
                busy--;
                served++;
                queue.finished(j);
                dispatch();
            } finally {
                lock.unlock();
            }
        }
    }

    private WorkerProcess acquire() {
        lock.lock();
        try {
            while (!idle.isEmpty()) {
                WorkerProcess w = idle.pollLast();
                if (w.alive()) {
                    return w;
                }
                all.remove(w);
            }
        } finally {
            lock.unlock();
        }
        return launch();
    }

    private WorkerProcess launch() {
        int n = numbering.incrementAndGet();
        String id = node + "/python#" + n;
        WorkerProcess w = WorkerProcess.start(spec(id));
        if (n > 1) {
            restarts.incrementAndGet();
        }
        lock.lock();
        try {
            all.add(w);
        } finally {
            lock.unlock();
        }
        return w;
    }

    private void release(WorkerProcess w, boolean keep) {
        lock.lock();
        try {
            if (keep && w.alive() && !closed) {
                idle.addLast(w);
                return;
            }
            all.remove(w);
        } finally {
            lock.unlock();
        }
        w.close();
    }

    /** Stops workers idle past the limit, down to the warm minimum. */
    private void reap() {
        List<WorkerProcess> stop = new ArrayList<>();
        long now = System.currentTimeMillis();
        lock.lock();
        try {
            int keepAtLeast = settings.workersMin();
            for (WorkerProcess w : new ArrayList<>(idle)) {
                if (!w.alive() || (idle.size() > keepAtLeast && now - w.lastUsed() > settings.idleStop().toMillis())) {
                    idle.remove(w);
                    all.remove(w);
                    stop.add(w);
                }
            }
        } finally {
            lock.unlock();
        }
        stop.forEach(WorkerProcess::close);
    }

    private WorkerProcess.Spec spec(String id) {
        PoolSettings.Mode mode = settings.mode().resolved();
        List<String> cmd = new ArrayList<>(List.of(settings.command(), "-s", "-B", "-m", "engine_python.worker", "--engines", String.join(",", engines),
                "--mode", mode == PoolSettings.Mode.FORK ? "fork" : "warm", "--memory", Long.toString(settings.memoryBytes()), "--cpu-seconds",
                Integer.toString(settings.cpuSeconds()), "--worker", id, "--isolate", settings.isolation()));
        return new WorkerProcess.Spec(id, cmd, workerEnv(), work, settings.startTimeout(), mode == PoolSettings.Mode.WARM);
    }

    /** The worker's whole environment: nothing inherited but the system path; no tokens, no credentials; temp files under the pool's folder. */
    private Map<String, String> workerEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        for (String k : List.of("PATH", "SYSTEMROOT", "WINDIR", "LANG", "LC_ALL")) {
            String v = System.getenv(k);
            if (v != null) {
                env.put(k, v);
            }
        }
        env.put("PYTHONPATH", sdk.toString());
        env.put("PYTHONHASHSEED", "0");
        env.put("PYTHONDONTWRITEBYTECODE", "1");
        env.put("PYTHONNOUSERSITE", "1");
        env.put("PYTHONIOENCODING", "utf-8");
        for (String k : List.of("OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS", "NUMEXPR_NUM_THREADS", "POLARS_MAX_THREADS")) {
            env.put(k, "1");                                                // the pool, not the library, decides parallelism
        }
        env.put("ARROW_DEFAULT_MEMORY_POOL", "system");                     // no allocator threads in a parent that forks
        String tmp = work.resolve("tmp").toString();
        env.put("TMPDIR", tmp);
        env.put("TEMP", tmp);
        env.put("TMP", tmp);
        env.put("HOME", work.toString());
        if (WINDOWS) {
            env.put("USERPROFILE", work.toString());
        }
        return env;
    }

    /** The worker's {@code run} control message. */
    private Map<String, Object> message(PoolRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "run");
        m.put("id", r.id());
        m.put("engine", r.engine());
        m.put("kind", r.kind());
        m.put("params", r.params());
        m.put("user", r.user());
        m.put("roles", r.roles());
        m.put("viewSignature", r.viewSignature());
        m.put("cls", r.cls().name().toLowerCase());
        m.put("seed", r.seed());
        m.put("scratch", r.scratch().dir().toString());
        m.put("inputs", r.inputs());
        if (r.cpuSeconds() > 0) {
            m.put("cpuSeconds", settings.cpuSeconds() > 0 ? Math.min(r.cpuSeconds(), settings.cpuSeconds()) : r.cpuSeconds());
        }
        return m;
    }

    public Stats stats() {
        lock.lock();
        try {
            return new Stats(all.size(), busy, idle.size(), queue.size(), served, restarts.get());
        } finally {
            lock.unlock();
        }
    }

    /** Words for the host's administrator: how many workers, how many busy, how many waiting. */
    public List<String> notes() {
        Stats s = stats();
        return List.of("python pool: " + s.workers() + " workers (" + s.busy() + " busy, " + s.idle() + " idle), " + s.waiting() + " waiting, "
                + s.served() + " served, isolation " + settings.isolation() + ", mode " + settings.mode().resolved().name().toLowerCase());
    }

    /** Stops taking new work; requests in flight finish, workers stay up (resume is then cheap). */
    public void suspend() {
        suspended = true;
    }

    /** Takes work again; the workers were kept, so nothing starts. */
    public void resume() {
        suspended = false;
    }

    public boolean suspended() {
        return suspended;
    }

    public boolean running() {
        return started && !closed;
    }

    @Override
    public void close() {
        List<WorkerProcess> stop;
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            stop = new ArrayList<>(all);
            all.clear();
            idle.clear();
            for (Job j : queue.waiting()) {
                queue.remove(j);
                j.future.completeExceptionally(new PoolException(Code.CANCELLED, "the pool is stopping", j.request.id(), null));
            }
        } finally {
            lock.unlock();
        }
        if (reaper != null) {
            reaper.shutdownNow();
        }
        stop.forEach(WorkerProcess::close);
        if (runners != null) {
            runners.shutdownNow();
        }
        try {
            if (Files.isDirectory(scratchRoot)) {
                Scratch.sweep(scratchRoot);
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "the pool's scratch folders could not be swept: {0}", e.getMessage());
        }
    }
}
