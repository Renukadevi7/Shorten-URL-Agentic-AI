package com.agentic.orchestration.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Builds an OpenAPI 3 YAML document from the live Spring MVC request mappings
 * (actual controller code), not from a hard-coded string.
 */
@Component
public class OpenApiFromCodeGenerator {

	private final RequestMappingHandlerMapping handlerMapping;

	public OpenApiFromCodeGenerator(RequestMappingHandlerMapping handlerMapping) {
		this.handlerMapping = handlerMapping;
	}

	public String toYaml() {
		// path -> method -> summary
		Map<String, Map<String, String>> paths = new TreeMap<>();
		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
			RequestMappingInfo info = entry.getKey();
			HandlerMethod handler = entry.getValue();
			Set<String> patterns = info.getPatternValues();
			Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
			if (patterns == null || patterns.isEmpty()) {
				continue;
			}
			if (methods == null || methods.isEmpty()) {
				methods = Set.of(RequestMethod.GET);
			}
			String summary = handler.getBeanType().getSimpleName() + "." + handler.getMethod().getName();
			for (String pattern : patterns) {
				if (pattern.startsWith("/error") || pattern.equals("/")) {
					continue;
				}
				Map<String, String> ops = paths.computeIfAbsent(pattern, p -> new TreeMap<>());
				for (RequestMethod method : methods) {
					ops.put(method.name().toLowerCase(), summary);
				}
			}
		}

		StringBuilder yaml = new StringBuilder();
		yaml.append("openapi: 3.0.3\n");
		yaml.append("info:\n");
		yaml.append("  title: Agentic URL Shortener API\n");
		yaml.append("  version: 1.0.0\n");
		yaml.append("  description: Generated from live Spring MVC request mappings\n");
		yaml.append("paths:\n");
		for (Map.Entry<String, Map<String, String>> path : paths.entrySet()) {
			yaml.append("  ").append(escapePath(path.getKey())).append(":\n");
			for (Map.Entry<String, String> op : path.getValue().entrySet()) {
				yaml.append("    ").append(op.getKey()).append(":\n");
				yaml.append("      summary: ").append(quote(op.getValue())).append("\n");
				yaml.append("      operationId: ").append(sanitizeOpId(path.getKey(), op.getKey())).append("\n");
				yaml.append("      responses:\n");
				yaml.append("        '200':\n");
				yaml.append("          description: OK\n");
			}
		}
		return yaml.toString();
	}

	/** Path keys present in the generated document (for assertions). */
	public Set<String> pathKeys() {
		Set<String> keys = new TreeSet<>();
		for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
			keys.addAll(info.getPatternValues());
		}
		keys.remove("/");
		keys.removeIf(p -> p.startsWith("/error"));
		return keys;
	}

	private static String escapePath(String path) {
		return path.contains("{") || path.contains(":") || path.contains("*") ? "'" + path + "'" : path;
	}

	private static String quote(String value) {
		return "\"" + value.replace("\"", "'") + "\"";
	}

	private static String sanitizeOpId(String path, String method) {
		return (method + "_" + path.replaceAll("[^a-zA-Z0-9]+", "_")).replaceAll("_+", "_");
	}
}
