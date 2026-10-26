package com.agentic.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.agentic.orchestration.OrchestrationRun;
import com.agentic.orchestration.OrchestratorService;

@RestController
@RequestMapping("/api/orchestration")
public class OrchestrationController {

	private final OrchestratorService orchestratorService;

	public OrchestrationController(OrchestratorService orchestratorService) {
		this.orchestratorService = orchestratorService;
	}

	@PostMapping("/runs")
	public Map<String, Object> start(@RequestBody StartRunRequest request) {
		try {
			String scenario = request.scenario() == null ? "greenfield" : request.scenario();
			String requirement = request.requirement() == null ? "" : request.requirement();
			return orchestratorService.start(scenario, requirement).toView();
		}
		catch (IllegalArgumentException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
		}
	}

	@GetMapping("/runs")
	public Object list() {
		return orchestratorService.list().stream().map(OrchestrationRun::toView).toList();
	}

	@GetMapping("/runs/{id}")
	public Map<String, Object> get(@PathVariable String id) {
		return orchestratorService.get(id)
				.map(OrchestrationRun::toView)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
	}

	@PostMapping("/runs/{id}/approve")
	public Map<String, Object> approve(@PathVariable String id, @RequestBody(required = false) ActorRequest body) {
		String actor = body == null || body.actor() == null ? "operator" : body.actor();
		try {
			return orchestratorService.approve(id, actor).toView();
		}
		catch (RuntimeException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
		}
	}

	@PostMapping("/runs/{id}/reject")
	public Map<String, Object> reject(@PathVariable String id, @RequestBody(required = false) ReasonRequest body) {
		String reason = body == null || body.reason() == null ? "rejected by operator" : body.reason();
		return orchestratorService.reject(id, reason).toView();
	}

	@PostMapping("/runs/{id}/resume")
	public Map<String, Object> resume(@PathVariable String id) {
		try {
			return orchestratorService.resume(id).toView();
		}
		catch (RuntimeException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
		}
	}

	@PostMapping("/runs/{id}/safe-stop")
	public Map<String, Object> safeStop(@PathVariable String id, @RequestBody(required = false) ReasonRequest body) {
		String reason = body == null || body.reason() == null ? "safe-stop requested" : body.reason();
		return orchestratorService.safeStop(id, reason).toView();
	}

	@PostMapping("/runs/{id}/fault")
	public Map<String, Object> fault(@PathVariable String id, @RequestBody FaultRequest body) {
		try {
			String type = body == null || body.type() == null ? "FAIL_TESTING" : body.type();
			boolean once = body == null || body.once() == null || body.once();
			return orchestratorService.injectFault(id, type, once).toView();
		}
		catch (RuntimeException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
		}
	}

	@PostMapping("/runs/{id}/mutate-upstream")
	public Map<String, Object> mutateUpstream(@PathVariable String id, @RequestBody MutateRequest body) {
		try {
			String requirement = body == null || body.requirement() == null ? "" : body.requirement();
			return orchestratorService.mutateUpstream(id, requirement).toView();
		}
		catch (RuntimeException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
		}
	}

	@GetMapping("/metrics")
	public Map<String, Object> metrics() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("reliability", orchestratorService.metrics());
		return out;
	}

	public record StartRunRequest(String scenario, String requirement) {
	}

	public record ActorRequest(String actor) {
	}

	public record ReasonRequest(String reason) {
	}

	public record FaultRequest(String type, Boolean once) {
	}

	public record MutateRequest(String requirement) {
	}
}
