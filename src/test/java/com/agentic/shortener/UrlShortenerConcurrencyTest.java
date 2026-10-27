package com.agentic.shortener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class UrlShortenerConcurrencyTest {

	@Autowired
	private UrlShortenerService service;

	@BeforeEach
	void reset() {
		service.resetProduct();
		service.enableAnalytics();
	}

	@Test
	void concurrentResolvesDoNotLoseClicks() throws Exception {
		ShortUrl created = service.shorten("https://example.com/clicks", "clickrace", null);
		int threads = 20;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		List<Callable<Boolean>> tasks = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			tasks.add(() -> service.resolve(created.code()).isPresent());
		}
		List<Future<Boolean>> futures = pool.invokeAll(tasks);
		pool.shutdown();
		int ok = 0;
		for (Future<Boolean> f : futures) {
			if (Boolean.TRUE.equals(f.get())) {
				ok++;
			}
		}
		assertEquals(threads, ok);
		assertEquals(threads, service.stats(created.code()).orElseThrow().clicks());
	}

	@Test
	void concurrentAliasCreatesOnlyOneWinner() throws Exception {
		int threads = 10;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		AtomicInteger success = new AtomicInteger();
		AtomicInteger conflicts = new AtomicInteger();
		List<Callable<Void>> tasks = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			tasks.add(() -> {
				try {
					service.shorten("https://example.com/" + Thread.currentThread().getId(), "samealias", null);
					success.incrementAndGet();
				}
				catch (RuntimeException ex) {
					conflicts.incrementAndGet();
				}
				return null;
			});
		}
		pool.invokeAll(tasks);
		pool.shutdown();
		assertEquals(1, success.get());
		assertTrue(conflicts.get() >= threads - 1);
		assertThrows(IllegalArgumentException.class,
				() -> service.shorten("https://example.com/again", "samealias", null));
	}
}
