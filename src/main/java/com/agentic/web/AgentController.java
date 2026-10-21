package com.agentic.web;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.agentic.agent.AgentRunEvent;
import com.agentic.agent.AgentRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api")
public class AgentController {

	private final AgentRuntime agentRuntime;
	private final ObjectMapper objectMapper;
	private final ExecutorService executor = Executors.newCachedThreadPool();

	public AgentController(AgentRuntime agentRuntime, ObjectMapper objectMapper) {
		this.agentRuntime = agentRuntime;
		this.objectMapper = objectMapper;
	}

	@PostMapping(path = "/agent", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter run(@RequestBody(required = false) AgentRunRequest request) {
		SseEmitter emitter = new SseEmitter(120_000L);
		String goal = request == null ? "" : request.goal();

		executor.execute(() -> {
			try {
				agentRuntime.run(goal, event -> {
					try {
						String json = objectMapper.writeValueAsString(event);
						emitter.send(SseEmitter.event().data(json, MediaType.TEXT_PLAIN));
					}
					catch (IOException ex) {
						throw new EmitException(ex);
					}
				});
				emitter.complete();
			}
			catch (EmitException ex) {
				emitter.completeWithError(ex.getCause());
			}
			catch (Exception ex) {
				try {
					String json = objectMapper.writeValueAsString(AgentRunEvent.error(
							ex.getMessage() == null ? "Agent run failed." : ex.getMessage()));
					emitter.send(SseEmitter.event().data(json, MediaType.TEXT_PLAIN));
				}
				catch (Exception ignored) {
					// ignore secondary failures
				}
				emitter.completeWithError(ex);
			}
		});

		return emitter;
	}

	private static final class EmitException extends RuntimeException {
		private EmitException(Throwable cause) {
			super(cause);
		}
	}
}
