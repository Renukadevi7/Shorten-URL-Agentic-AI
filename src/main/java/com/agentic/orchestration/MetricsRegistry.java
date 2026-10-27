package com.agentic.orchestration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

@Component
public class MetricsRegistry {

	private final AtomicInteger started = new AtomicInteger();
	private final AtomicInteger completed = new AtomicInteger();
	private final AtomicInteger failed = new AtomicInteger();
	private final AtomicInteger retries = new AtomicInteger();
	private final AtomicInteger rollbacks = new AtomicInteger();
	private final AtomicLong totalLatencyMs = new AtomicLong();
	private final AtomicLong totalMttrMs = new AtomicLong();
	private final AtomicInteger mttrSamples = new AtomicInteger();

	public void onStart() {
		started.incrementAndGet();
	}

	public void onComplete(long latencyMs) {
		completed.incrementAndGet();
		totalLatencyMs.addAndGet(latencyMs);
	}

	/** Record a failed/stopped run latency only — MTTR is recorded separately on recovery. */
	public void onFail(long latencyMs) {
		failed.incrementAndGet();
		totalLatencyMs.addAndGet(latencyMs);
	}

	/** MTTR sample: elapsed time from first failure to successful recovery. */
	public void onRecovery(long mttrMs) {
		if (mttrMs >= 0) {
			totalMttrMs.addAndGet(mttrMs);
			mttrSamples.incrementAndGet();
		}
	}

	public void onRetry() {
		retries.incrementAndGet();
	}

	public void onRollback() {
		rollbacks.incrementAndGet();
	}

	public Map<String, Object> snapshot() {
		int done = completed.get() + failed.get();
		double successRate = done == 0 ? 0.0 : (completed.get() * 100.0) / done;
		long avgLatency = done == 0 ? 0 : totalLatencyMs.get() / done;
		long avgMttr = mttrSamples.get() == 0 ? 0 : totalMttrMs.get() / mttrSamples.get();
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("runsStarted", started.get());
		map.put("runsCompleted", completed.get());
		map.put("runsFailed", failed.get());
		map.put("successRatePercent", Math.round(successRate * 10.0) / 10.0);
		map.put("retryCount", retries.get());
		map.put("rollbackCount", rollbacks.get());
		map.put("avgEndToEndLatencyMs", avgLatency);
		map.put("avgMttrMs", avgMttr);
		map.put("mttrSamples", mttrSamples.get());
		return map;
	}
}
