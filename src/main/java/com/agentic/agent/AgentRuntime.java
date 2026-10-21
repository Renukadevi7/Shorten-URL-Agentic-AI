package com.agentic.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

@Service
public class AgentRuntime {

	private static final Pattern MATH_PATTERN = Pattern.compile(
			"(-?\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(-?\\d+(?:\\.\\d+)?)");
	private static final Pattern CITY_IN = Pattern.compile("in ([A-Za-z .'-]+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern CITY_FOR = Pattern.compile("for ([A-Za-z .'-]+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern LOOSE_EXPR = Pattern.compile("[\\d.+\\-*/()\\s%]+");

	private final Map<String, AgentTool> toolsByName;
	private final List<AgentTool> tools;

	public AgentRuntime(List<AgentTool> tools) {
		this.tools = List.copyOf(tools);
		this.toolsByName = tools.stream().collect(Collectors.toUnmodifiableMap(AgentTool::name, t -> t));
	}

	public void run(String goal, Consumer<AgentRunEvent> emit) {
		String trimmed = goal == null ? "" : goal.trim();
		if (trimmed.isEmpty()) {
			emit.accept(AgentRunEvent.error("Enter a goal for the agent to pursue."));
			return;
		}

		List<AgentStep> steps = new ArrayList<>();

		AgentStep planIntro = newStep(AgentStepKind.PLAN, "Drafting a plan",
				"Breaking the goal into tool calls. Available tools: "
						+ tools.stream().map(AgentTool::name).collect(Collectors.joining(", ")),
				null, StepStatus.RUNNING);
		steps.add(planIntro);
		emit.accept(AgentRunEvent.step(planIntro));

		List<PlannedAction> actions = planActions(trimmed);
		String planDetail = "Planned " + actions.size() + " action" + (actions.size() == 1 ? "" : "s") + ":\n"
				+ formatActions(actions);
		planIntro = planIntro.withDetailAndStatus(planDetail, StepStatus.DONE);
		steps.set(0, planIntro);
		emit.accept(AgentRunEvent.step(planIntro));

		List<Observation> observations = new ArrayList<>();

		for (PlannedAction action : actions) {
			AgentStep toolStep = newStep(AgentStepKind.TOOL, "Calling " + action.toolName(),
					"Input: " + action.input(), action.toolName(), StepStatus.RUNNING);
			steps.add(toolStep);
			emit.accept(AgentRunEvent.step(toolStep));

			AgentTool tool = toolsByName.get(action.toolName());
			if (tool == null) {
				toolStep = toolStep.withDetailAndStatus("Unknown tool: " + action.toolName(), StepStatus.ERROR);
				steps.set(steps.size() - 1, toolStep);
				emit.accept(AgentRunEvent.step(toolStep));
				emit.accept(AgentRunEvent.error("Unknown tool: " + action.toolName()));
				return;
			}

			ToolResult result = tool.run(action.input());
			toolStep = toolStep.withDetailAndStatus(result.output(),
					result.ok() ? StepStatus.DONE : StepStatus.ERROR);
			steps.set(steps.size() - 1, toolStep);
			emit.accept(AgentRunEvent.step(toolStep));

			AgentStep observe = newStep(AgentStepKind.OBSERVE, "Observed " + action.toolName(), result.output(),
					action.toolName(), result.ok() ? StepStatus.DONE : StepStatus.ERROR);
			steps.add(observe);
			emit.accept(AgentRunEvent.step(observe));

			if (!result.ok()) {
				emit.accept(AgentRunEvent.error("Tool " + action.toolName() + " failed: " + result.output()));
				return;
			}

			observations.add(new Observation(action.toolName(), result.output()));
		}

		String answerText = synthesize(trimmed, observations);
		AgentStep answerStep = newStep(AgentStepKind.ANSWER, "Final answer", answerText, null, StepStatus.DONE);
		steps.add(answerStep);
		emit.accept(AgentRunEvent.step(answerStep));
		emit.accept(AgentRunEvent.done(answerText, List.copyOf(steps)));
	}

	private List<PlannedAction> planActions(String goal) {
		String g = goal.toLowerCase(Locale.ROOT);
		List<PlannedAction> actions = new ArrayList<>();

		Matcher math = MATH_PATTERN.matcher(goal);
		boolean hasMath = math.find();
		if (hasMath || g.matches(".*(calculat|how much|sum|product|divide|multiply|percent|%).*")) {
			String expr;
			if (hasMath) {
				expr = math.group(0);
			}
			else {
				Matcher loose = LOOSE_EXPR.matcher(goal);
				expr = loose.find() ? loose.group().trim() : "12 * 8";
			}
			actions.add(new PlannedAction("calculator", expr, "Goal includes a numeric question."));
		}

		if (g.matches(".*(weather|temperature|forecast|rain|°|celsius|fahrenheit).*")) {
			String city = extractCity(goal);
			actions.add(new PlannedAction("weather_mock", city, "Goal asks about weather conditions."));
		}

		if (g.matches(".*(agent|agentic|react|tool|plan|loop|orchestrat|autonomous).*") || actions.isEmpty()) {
			String topic = g.contains("react") ? "react"
					: g.contains("tool") ? "tools" : g.contains("plan") ? "planning" : "agentic";
			actions.add(new PlannedAction("knowledge_search", topic,
					"Pull grounded notes from the local knowledge base."));
		}

		String note = goal.length() > 160 ? goal.substring(0, 160) : goal;
		actions.add(new PlannedAction("notes", "Working goal: " + note,
				"Keep a short working memory note before answering."));

		Set<String> seen = new LinkedHashSet<>();
		List<PlannedAction> unique = new ArrayList<>();
		for (PlannedAction action : actions) {
			String key = action.toolName() + ":" + action.input();
			if (seen.add(key)) {
				unique.add(action);
			}
		}
		return unique;
	}

	private static String extractCity(String goal) {
		Matcher in = CITY_IN.matcher(goal);
		if (in.find()) {
			return cleanCity(in.group(1));
		}
		Matcher forCity = CITY_FOR.matcher(goal);
		if (forCity.find()) {
			return cleanCity(forCity.group(1));
		}
		return "Lisbon";
	}

	private static String cleanCity(String raw) {
		return raw.replaceAll("[?.!].*$", "").trim();
	}

	private static String synthesize(String goal, List<Observation> observations) {
		StringBuilder lines = new StringBuilder();
		lines.append("Here’s what the agent did for: “").append(goal.trim()).append("”\n\n");
		lines.append("Observations:\n");
		for (int i = 0; i < observations.size(); i++) {
			Observation o = observations.get(i);
			lines.append(i + 1).append(". [").append(o.tool()).append("] ").append(o.output()).append('\n');
		}
		lines.append("\nAnswer:\n");

		Observation calc = find(observations, "calculator");
		Observation weather = find(observations, "weather_mock");
		Observation knowledge = find(observations, "knowledge_search");

		boolean hasContent = false;
		if (calc != null) {
			lines.append("The calculated result is ").append(calc.output()).append(".\n");
			hasContent = true;
		}
		if (weather != null) {
			lines.append(weather.output()).append('\n');
			hasContent = true;
		}
		if (knowledge != null) {
			lines.append(knowledge.output()).append('\n');
			hasContent = true;
		}
		if (!hasContent) {
			lines.append(
					"I gathered working notes but need a clearer goal to produce a sharper answer.\n");
		}
		else {
			lines.append(
					"This run used a local heuristic planner (no API key). Swap in a model later for open-ended goals.\n");
		}
		return lines.toString().trim();
	}

	private static Observation find(List<Observation> observations, String tool) {
		return observations.stream().filter(o -> tool.equals(o.tool())).findFirst().orElse(null);
	}

	private static String formatActions(List<PlannedAction> actions) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < actions.size(); i++) {
			PlannedAction a = actions.get(i);
			sb.append(i + 1).append(". ").append(a.toolName()).append(" — ").append(a.why());
			if (i < actions.size() - 1) {
				sb.append('\n');
			}
		}
		return sb.toString();
	}

	private static AgentStep newStep(AgentStepKind kind, String title, String detail, String toolName,
			StepStatus status) {
		return new AgentStep("s_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8), kind, title,
				detail, toolName, status, System.currentTimeMillis());
	}

	private record PlannedAction(String toolName, String input, String why) {
	}

	private record Observation(String tool, String output) {
	}
}
