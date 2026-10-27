package com.agentic.orchestration.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.agentic.orchestration.OrchestrationRun;
import com.agentic.orchestration.OrchestratorService;
import com.agentic.orchestration.RunStatus;
import com.agentic.shortener.AliasRulesHolder;
import com.agentic.shortener.UrlShortenerService;
import com.sun.net.httpserver.HttpServer;

/**
 * Live HTTP model path: ChangeAgent talks to a real local HTTP endpoint (HttpModelClient).
 */
@SpringBootTest(properties = {
		"agentic.api-key=local-test-key-16c",
		"agentic.model.api-key=model-secret-token"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpModelClientEndToEndTest {

	private static HttpServer server;
	private static final AtomicReference<String> lastAuth = new AtomicReference<>();

	private static final String VALID_SOURCE = """
			package com.agentic.generated;
			import com.agentic.shortener.AliasRules;
			import java.util.Locale;
			public final class GeneratedAliasRules implements AliasRules {
			  public boolean isValid(String alias) {
			    if (alias == null || !AliasRules.PATTERN.matcher(alias).matches()) {
			      return false;
			    }
			    if (AliasRules.DEFAULT_RESERVED.contains(alias.toLowerCase(Locale.ROOT))) {
			      return false;
			    }
			    return true;
			  }
			}
			""";

	@DynamicPropertySource
	static void modelUrl(DynamicPropertyRegistry registry) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/draft", exchange -> {
			lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
			byte[] body = ("{\"source\":"
					+ new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(VALID_SOURCE)
					+ "}").getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(body);
			}
		});
		server.start();
		int port = server.getAddress().getPort();
		registry.add("agentic.model.url", () -> "http://127.0.0.1:" + port + "/v1/draft");
	}

	@AfterAll
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Autowired
	private OrchestratorService orchestrator;

	@Autowired
	private ModelClient modelClient;

	@Autowired
	private UrlShortenerService shortener;

	@Autowired
	private AliasRulesHolder aliasRulesHolder;

	@BeforeEach
	void reset() {
		shortener.resetProduct();
		aliasRulesHolder.reset();
		lastAuth.set(null);
	}

	@Test
	void liveHttpModelClientProducesValidatedChange() {
		assertTrue(modelClient instanceof HttpModelClient);
		OrchestrationRun run = orchestrator.start("greenfield",
				"Build a URL shortener with create and redirect APIs over HTTPS.");
		assertEquals(RunStatus.AWAITING_APPROVAL, run.status());
		@SuppressWarnings("unchecked")
		Map<String, Object> artifact = (Map<String, Object>) run.stages().get("impl").artifacts().get("changeArtifact");
		assertEquals("HttpModelClient", artifact.get("operations") instanceof Map<?, ?> ops
				? ops.get("modelClient")
				: null);
		assertTrue(String.valueOf(artifact.get("apiSpecification")).contains("/api/urls"));
		assertTrue(String.valueOf(artifact.get("apiSpecification")).contains("Generated from live Spring MVC"));
		assertEquals("Bearer model-secret-token", lastAuth.get());
		assertTrue(Boolean.TRUE.equals(run.context().get("aliasRulesInstalled")));
	}
}
