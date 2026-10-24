package com.spring.gateway.observability;

import io.github.sercasti.tracing.core.Metric;
import io.github.sercasti.tracing.core.Tracing;
import io.github.sercasti.tracing.core.TracingImpl;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 请求计时上下文。
 *
 * @author hanbing
 * @since 2026-10-24
 */
public final class RequestTimings {
    static final String ATTRIBUTE = RequestTimings.class.getName();
    private static final ThreadLocal<RequestTimings> CURRENT = new ThreadLocal<>();
    private static final Pattern DURATION = Pattern.compile(";dur=([0-9.Ee+-]+)");
    /** 单请求最多记录的计时节点数。 */
    static final int MAX_METRICS = 32;
    private final TracingImpl tracing = new TracingImpl();
    private final List<Metric> metrics = new ArrayList<>();
    private final Map<String, Integer> counts = new HashMap<>();
    private boolean enabled;
    private String header;

    RequestTimings(boolean enabled) {
        this.enabled = enabled;
        startMetric("total", "Until response starts");
    }

    static RequestTimings current() {
        return CURRENT.get();
    }

    static void bind(RequestTimings timings) {
        if (timings == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(timings);
        }
    }

    void enable() {
        enabled = true;
    }

    public static Span start(String stage, String description) {
        RequestTimings timings = current();
        return timings == null ? new Span(null) : timings.startMetric(stage, description);
    }

    private synchronized Span startMetric(String stage, String description) {
        if (header != null || metrics.size() >= MAX_METRICS || stage == null
                || !stage.matches("[A-Za-z][A-Za-z0-9_-]{0,31}")) {
            return new Span(null);
        }
        int count = counts.merge(stage, 1, Integer::sum);
        String name = count == 1 ? stage : stage + "_" + count;
        String safeDescription = description;
        if (safeDescription != null) {
            safeDescription = safeDescription.substring(0, Math.min(72, safeDescription.length()))
                    .replaceAll("[^\\x20-\\x7E]", " ").replace("\\", "\\\\").replace("\"", "\\\"");
        }
        Metric metric = tracing.start(name, safeDescription);
        metrics.add(metric);
        return new Span(metric);
    }

    synchronized void writeHeader(HttpServletResponse response) {
        if (!enabled || response.isCommitted()) {
            return;
        }
        if (header == null) {
            metrics.forEach(RequestTimings::stop);
            tracing.dump(new HttpServletResponseWrapper(response) {
                @Override
                public void addHeader(String name, String value) {
                    if (Tracing.SERVER_TIMING_HEADER.equalsIgnoreCase(name)) {
                        header = DURATION.matcher(value).replaceAll(match -> ";dur="
                                + new BigDecimal(match.group(1)).max(BigDecimal.ZERO).toPlainString());
                    }
                }
            }, null);
        }
        if (header != null && !response.getHeaders(Tracing.SERVER_TIMING_HEADER).contains(header)) {
            response.addHeader(Tracing.SERVER_TIMING_HEADER, header);
        }
    }

    private static void stop(Metric metric) {
        synchronized (metric) {
            if (metric.getDuration() == null) {
                metric.stop();
            }
        }
    }

    /**
     * 单个调用的计时区间。
     *
     * @author hanbing
     * @since 2026-10-24
     */
    public static final class Span implements AutoCloseable {
        private final Metric metric;

        private Span(Metric metric) {
            this.metric = metric;
        }

        @Override
        public void close() {
            if (metric != null) {
                stop(metric);
            }
        }
    }
}
