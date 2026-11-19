package com.dsh.sensorkit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 软实时内核层（类 RT-Thread）：
 * 调度器 + 线程对象 + 设备框架 + 邮箱/信号量/互斥量 + 软定时器 + 节拍抖动统计。
 * 跑在 Android 线程之上，属于软实时，不是硬实时。
 */
public final class Rtos {

    public static final int TICK_MS = 10;

    public enum State { READY, RUNNING, SUSPEND, EXIT }

    public enum ObjType {
        THREAD("线程"), SEMAPHORE("信号量"), MUTEX("互斥量"),
        MAILBOX("邮箱"), TIMER("定时器"), DEVICE("设备");
        public final String cn;
        ObjType(String cn) { this.cn = cn; }
    }

    // ---------------- 内核对象 ----------------
    public abstract static class Obj {
        public final String name;
        public final ObjType type;
        public final long id;
        protected Obj(String name, ObjType type, long id) {
            this.name = name;
            this.type = type;
            this.id = id;
            OBJECTS.get(type).add(this);
        }
    }

    private static long nextId = 1;

    private static synchronized long newId() {
        return nextId++;
    }

    /** 全局对象容器，按类型统计，等价于 rt_object_container。 */
    public static final Map<ObjType, List<Obj>> OBJECTS = new LinkedHashMap<>();

    static {
        for (ObjType t : ObjType.values()) OBJECTS.put(t, Collections.synchronizedList(new ArrayList<>()));
    }

    public static List<Obj> objects(ObjType t) {
        List<Obj> l = OBJECTS.get(t);
        synchronized (l) {
            return new ArrayList<>(l);
        }
    }

    public static void clearObjects(ObjType t) {
        OBJECTS.get(t).clear();
    }

    public static int objectCount() {
        int n = 0;
        for (List<Obj> l : OBJECTS.values()) n += l.size();
        return n;
    }

    // ---------------- 线程（任务） ----------------
    public static final class Task extends Obj {
        public final int priority;
        public final int periodMs;       // 0 表示事件驱动
        public volatile State state = State.READY;
        public volatile boolean suspended;
        public volatile long wakeups;
        public volatile long cpuNanos;
        public volatile long jitterUs;
        public volatile long jitterMaxUs;
        public volatile int stackKb = 64;
        private volatile boolean running = true;
        private final Runnable body;
        private Thread thread;
        private long lastStartNs;

        Task(String name, int priority, int periodMs, Runnable body) {
            super(name, ObjType.THREAD, newId());
            this.priority = priority;
            this.periodMs = periodMs;
            this.body = body;
        }

        void start() {
            thread = new Thread(() -> {
                while (running) {
                    synchronized (this) {
                        while (suspended && running) {
                            state = State.SUSPEND;
                            try { wait(200); } catch (InterruptedException ignored) { }
                        }
                    }
                    if (!running) break;
                    long now = System.nanoTime();
                    if (lastStartNs != 0 && periodMs > 0) {
                        long intervalUs = (now - lastStartNs) / 1000L;
                        long j = Math.abs(intervalUs - periodMs * 1000L);
                        jitterUs = (jitterUs * 7 + j) / 8;
                        if (j > jitterMaxUs) jitterMaxUs = j;
                    }
                    lastStartNs = now;
                    state = State.RUNNING;
                    long t0 = System.nanoTime();
                    try {
                        body.run();
                        wakeups++;
                    } catch (Throwable ignored) {
                    }
                    cpuNanos += System.nanoTime() - t0;
                    if (running) state = State.READY;
                }
                state = State.EXIT;
            }, "rt-" + name);
            thread.setDaemon(true);
            thread.start();
        }

        public void suspend() {
            suspended = true;
        }

        public void resume() {
            synchronized (this) {
                suspended = false;
                state = State.READY;
                notifyAll();
            }
        }

        public void delete() {
            running = false;
            synchronized (this) { notifyAll(); }
            state = State.EXIT;
            OBJECTS.get(ObjType.THREAD).remove(this);
        }

        public boolean alive() { return running; }

        public double cpuPercent() {
            long up = Scheduler.uptimeMs();
            if (up <= 0) return 0;
            return Math.min(100.0, cpuNanos / 1_000_000.0 * 100.0 / up);
        }

        public String stateCn() {
            if (suspended) return "挂起";
            switch (state) {
                case RUNNING: return "运行";
                case READY: return "就绪";
                case EXIT: return "退出";
                default: return "—";
            }
        }
    }

    // ---------------- 调度器 ----------------
    public static final class Scheduler {
        private static final long START_NS = System.nanoTime();
        private static long ticks;
        private static long ctxSwitches;
        private static long tickJitterUs;
        private static long tickJitterMaxUs;
        private static long lastTickNs;
        private static volatile boolean paused;
        private static ScheduledExecutorService ticker;

        public static synchronized void init() {
            if (ticker != null) return;
            ticker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "rt-tick");
                t.setDaemon(true);
                t.setPriority(Thread.MAX_PRIORITY);
                return t;
            });
            ticker.scheduleAtFixedRate(() -> {
                long now = System.nanoTime();
                if (lastTickNs != 0) {
                    long d = Math.abs((now - lastTickNs) / 1000L - TICK_MS * 1000L);
                    tickJitterUs = (tickJitterUs * 15 + d) / 16;
                    if (d > tickJitterMaxUs) tickJitterMaxUs = d;
                }
                lastTickNs = now;
                if (paused) return;
                ticks++;
                List<Task> tasks = tasks();
                ctxSwitches += tasks.size();
                for (Timer t : timers()) {
                    if (!t.running) continue;
                    if (ticks % Math.max(1, t.periodMs / TICK_MS) == 0) {
                        t.triggered++;
                        try { t.body.run(); } catch (Throwable ignored) { }
                    }
                }
            }, 0, TICK_MS, TimeUnit.MILLISECONDS);
        }

        public static Task create(String name, int priority, int periodMs, Runnable body) {
            Task t = new Task(name, priority, periodMs, body);
            t.start();
            return t;
        }

        public static List<Task> tasks() {
            List<Task> out = new ArrayList<>();
            for (Obj o : objects(ObjType.THREAD)) out.add((Task) o);
            return out;
        }

        public static void stopAll() {
            for (Task t : tasks()) t.delete();
            if (ticker != null) {
                ticker.shutdownNow();
                ticker = null;
            }
        }

        public static void setPaused(boolean p) { paused = p; }
        public static boolean paused() { return paused; }
        public static long uptimeMs() { return (System.nanoTime() - START_NS) / 1_000_000L; }
        public static long ticks() { return ticks; }
        public static long ctxSwitches() { return ctxSwitches; }
        public static long tickJitterUs() { return tickJitterUs; }
        public static long tickJitterMaxUs() { return tickJitterMaxUs; }
    }

    // ---------------- 软定时器 ----------------
    public static final class Timer extends Obj {
        public final int periodMs;
        public volatile long triggered;
        public volatile boolean running = true;
        final Runnable body;

        Timer(String name, int periodMs, Runnable body) {
            super(name, ObjType.TIMER, newId());
            this.periodMs = periodMs;
            this.body = body;
        }

        public void stop() {
            running = false;
            OBJECTS.get(ObjType.TIMER).remove(this);
        }
    }

    public static Timer createTimer(String name, int periodMs, Runnable body) {
        Timer t = new Timer(name, periodMs, body);
        OBJECTS.get(ObjType.TIMER).add(t);
        return t;
    }

    public static List<Timer> timers() {
        List<Timer> out = new ArrayList<>();
        for (Obj o : objects(ObjType.TIMER)) out.add((Timer) o);
        return out;
    }

    // ---------------- 设备 ----------------
    public static final class Device extends Obj {
        public final String typeCn;
        public final String vendor;
        public final int androidType;
        public volatile boolean open = true;
        public volatile int rateMs = 100;
        public volatile float[] values = new float[0];
        public volatile long reads;
        public volatile long lastReadNs;
        public volatile long maxIntervalMs;
        public volatile long avgIntervalUs;

        public Device(String name, String typeCn, String vendor, int androidType) {
            super(name, ObjType.DEVICE, newId());
            this.typeCn = typeCn;
            this.vendor = vendor;
            this.androidType = androidType;
        }

        public void feed(float[] v) {
            long now = System.nanoTime();
            if (lastReadNs != 0) {
                long ms = (now - lastReadNs) / 1_000_000L;
                if (ms > maxIntervalMs) maxIntervalMs = ms;
                avgIntervalUs = (avgIntervalUs * 3 + (now - lastReadNs) / 1000L) / 4;
            }
            lastReadNs = now;
            values = v;
            reads++;
        }

        public String valueText() {
            float[] v = values;
            if (v == null || v.length == 0) return "等待数据";
            if (androidType == android.hardware.Sensor.TYPE_PROXIMITY)
                return String.format(Locale.CHINA, "%.0f cm", v[0]);
            if (androidType == android.hardware.Sensor.TYPE_LIGHT)
                return String.format(Locale.CHINA, "%.0f lx", v[0]);
            if (androidType == android.hardware.Sensor.TYPE_PRESSURE)
                return String.format(Locale.CHINA, "%.1f hPa", v[0]);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(3, v.length); i++) {
                if (i > 0) sb.append("  ");
                sb.append(String.format(Locale.CHINA, "%.2f", v[i]));
            }
            return sb.toString();
        }
    }

    public static Device createDevice(String name, String typeCn, String vendor, int androidType) {
        Device d = new Device(name, typeCn, vendor, androidType);
        OBJECTS.get(ObjType.DEVICE).add(d);
        return d;
    }

    public static List<Device> devices() {
        List<Device> out = new ArrayList<>();
        for (Obj o : objects(ObjType.DEVICE)) out.add((Device) o);
        return out;
    }

    public static int openDevices() {
        int n = 0;
        for (Device d : devices()) if (d.open) n++;
        return n;
    }

    // ---------------- IPC ----------------
    public static final class Mailbox extends Obj {
        private final ArrayDeque<Object> queue = new ArrayDeque<>();
        public final int capacity;
        public volatile long sent, dropped, peak;

        public Mailbox(String name, int cap) {
            super(name, ObjType.MAILBOX, newId());
            this.capacity = cap;
        }

        public synchronized boolean put(Object o) {
            if (queue.size() >= capacity) { dropped++; return false; }
            queue.addLast(o);
            sent++;
            if (queue.size() > peak) peak = queue.size();
            notifyAll();
            return true;
        }

        public synchronized Object take() {
            return queue.pollFirst();
        }

        public synchronized int size() { return queue.size(); }
    }

    public static final class Semaphore extends Obj {
        private int count;
        public volatile long takes, timeouts;
        public final int init;

        public Semaphore(String name, int init) {
            super(name, ObjType.SEMAPHORE, newId());
            this.init = init;
            this.count = init;
        }

        public synchronized void release() {
            count++;
            notifyAll();
        }

        public synchronized boolean take(long timeoutMs) {
            long end = System.currentTimeMillis() + timeoutMs;
            while (count <= 0) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) { timeouts++; return false; }
                try { wait(left); } catch (InterruptedException e) { return false; }
            }
            count--;
            takes++;
            return true;
        }

        public synchronized int value() { return count; }
    }

    public static final class Mutex extends Obj {
        public volatile String owner = null;
        public volatile long locks;
        public volatile long contentions;

        public Mutex(String name) {
            super(name, ObjType.MUTEX, newId());
        }

        public synchronized void lock(String who) {
            while (owner != null) {
                contentions++;
                try { wait(200); } catch (InterruptedException ignored) { }
            }
            owner = who;
            locks++;
        }

        public synchronized void unlock() {
            owner = null;
            notifyAll();
        }
    }

    // ---------------- 日志环 ----------------
    public static final class RingLog {
        private final String[] buf;
        private int idx, size, cap;

        public RingLog(int cap) {
            this.cap = cap;
            this.buf = new String[cap];
        }

        public synchronized void add(String line) {
            buf[idx] = line;
            idx = (idx + 1) % cap;
            if (size < cap) size++;
        }

        public synchronized List<String> newest(int n) {
            List<String> out = new ArrayList<>();
            int cnt = Math.min(n, size);
            for (int i = 0; i < cnt; i++) out.add(buf[(idx - 1 - i + cap * 2) % cap]);
            return out;
        }

        public synchronized int size() { return size; }
    }

    private Rtos() {
    }
}
