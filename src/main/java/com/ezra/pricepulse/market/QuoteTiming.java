package com.ezra.pricepulse.market;

/** Optional, thread-confined measurement hook. No samples, logging or allocation when unbound. */
public final class QuoteTiming {
    private QuoteTiming() { }
    public interface Sink { void record(String phase, long nanos); }
    private static final ThreadLocal<Sink> CURRENT = new ThreadLocal<>();
    public static AutoCloseable bind(Sink sink) {
        Sink previous = CURRENT.get();
        CURRENT.set(sink);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    public static long start() { return CURRENT.get() == null ? 0 : System.nanoTime(); }
    public static void finish(String phase, long started) {
        if (started == 0) return;
        Sink sink = CURRENT.get();
        if (sink != null) {
            try { sink.record(phase, System.nanoTime() - started); }
            catch (RuntimeException ignored) { /* Observability must not change a business decision. */ }
        }
    }
}
