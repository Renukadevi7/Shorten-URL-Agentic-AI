package com.agentic.orchestration;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class StageGraphLoader {

	private final ObjectMapper objectMapper;

	public StageGraphLoader(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public StageGraph load(String scenario) {
		String path = "orchestration/graphs/" + scenario + ".json";
		try (InputStream in = new ClassPathResource(path).getInputStream()) {
			Map<String, Object> root = objectMapper.readValue(in, new TypeReference<>() {
			});
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> stages = (List<Map<String, Object>>) root.get("stages");
			if (stages == null || stages.isEmpty()) {
				throw new IllegalStateException("Graph " + path + " has no stages");
			}
			StageGraph graph = new StageGraph();
			for (Map<String, Object> raw : stages) {
				graph.add(toNode(raw));
			}
			return graph;
		}
		catch (IOException ex) {
			throw new IllegalStateException("Unable to load stage graph: " + path, ex);
		}
	}

	private StageNode toNode(Map<String, Object> raw) {
		String id = String.valueOf(raw.get("id"));
		StageType type = StageType.valueOf(String.valueOf(raw.get("type")));
		String title = String.valueOf(raw.get("title"));
		List<String> dependsOn = raw.get("dependsOn") instanceof List<?> list
				? list.stream().map(String::valueOf).toList()
				: List.of();
		boolean approval = Boolean.TRUE.equals(raw.get("requiresHumanApproval"));
		String fallback = raw.get("fallbackStageId") == null ? null : String.valueOf(raw.get("fallbackStageId"));
		StageNode.Builder b = StageNode.builder(id, type, title).dependsOn(dependsOn).approval(approval);
		if (fallback != null && !"null".equals(fallback)) {
			b.fallback(fallback);
		}
		if (Boolean.TRUE.equals(raw.get("activationOnly"))) {
			b.activationOnly();
		}
		if (Boolean.TRUE.equals(raw.get("completesRun"))) {
			b.completesRun();
		}
		if (raw.get("entryContext") instanceof List<?> list) {
			b.entryContext(list.stream().map(String::valueOf).toArray(String[]::new));
		}
		if (raw.get("exitContext") instanceof List<?> list) {
			b.exitContext(list.stream().map(String::valueOf).toArray(String[]::new));
		}
		if (raw.get("exitArtifacts") instanceof List<?> list) {
			b.exitArtifacts(list.stream().map(String::valueOf).toArray(String[]::new));
		}
		if (raw.get("produces") instanceof List<?> list) {
			b.produces(list.stream().map(String::valueOf).toArray(String[]::new));
		}
		if (raw.get("consumes") instanceof List<?> list) {
			b.consumes(list.stream().map(String::valueOf).toArray(String[]::new));
		}
		return b.build();
	}
}
