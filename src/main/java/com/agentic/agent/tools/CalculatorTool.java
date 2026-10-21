package com.agentic.agent.tools;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.springframework.stereotype.Component;

import com.agentic.agent.AgentTool;
import com.agentic.agent.ToolResult;

@Component
public class CalculatorTool implements AgentTool {

	@Override
	public String name() {
		return "calculator";
	}

	@Override
	public String description() {
		return "Evaluate a simple arithmetic expression.";
	}

	@Override
	public ToolResult run(String expression) {
		sleep(200);
		if (expression == null || expression.isBlank()) {
			return ToolResult.failure("Empty expression.");
		}

		String cleaned = expression.replaceAll("[^0-9+\\-*/().%\\s]", "").trim();
		if (cleaned.isEmpty()) {
			return ToolResult.failure("Empty expression.");
		}

		try {
			double value = evaluate(cleaned);
			if (!Double.isFinite(value)) {
				return ToolResult.failure("Result was not a finite number.");
			}
			if (value == Math.rint(value) && Math.abs(value) < 1e15) {
				return ToolResult.success(Long.toString((long) value));
			}
			return ToolResult.success(Double.toString(value));
		}
		catch (RuntimeException ex) {
			return ToolResult.failure("Could not evaluate: " + expression);
		}
	}

	/**
	 * Tiny safe arithmetic evaluator (no scripting engine).
	 */
	static double evaluate(String expression) {
		List<String> tokens = tokenize(expression);
		List<String> rpn = toRpn(tokens);
		return evalRpn(rpn);
	}

	private static List<String> tokenize(String expression) {
		List<String> tokens = new ArrayList<>();
		StringBuilder number = new StringBuilder();
		for (int i = 0; i < expression.length(); i++) {
			char c = expression.charAt(i);
			if (Character.isWhitespace(c)) {
				continue;
			}
			if (Character.isDigit(c) || c == '.') {
				number.append(c);
				continue;
			}
			flushNumber(number, tokens);
			if ("+-*/%()".indexOf(c) >= 0) {
				tokens.add(String.valueOf(c));
			}
			else {
				throw new IllegalArgumentException("Bad char: " + c);
			}
		}
		flushNumber(number, tokens);
		return tokens;
	}

	private static void flushNumber(StringBuilder number, List<String> tokens) {
		if (number.length() > 0) {
			tokens.add(number.toString());
			number.setLength(0);
		}
	}

	private static int precedence(String op) {
		return switch (op) {
			case "+", "-" -> 1;
			case "*", "/", "%" -> 2;
			default -> 0;
		};
	}

	private static List<String> toRpn(List<String> tokens) {
		List<String> output = new ArrayList<>();
		Deque<String> ops = new ArrayDeque<>();
		for (String token : tokens) {
			if (isNumber(token)) {
				output.add(token);
			}
			else if ("+-*/%".contains(token)) {
				while (!ops.isEmpty() && precedence(ops.peek()) >= precedence(token)) {
					output.add(ops.pop());
				}
				ops.push(token);
			}
			else if ("(".equals(token)) {
				ops.push(token);
			}
			else if (")".equals(token)) {
				while (!ops.isEmpty() && !"(".equals(ops.peek())) {
					output.add(ops.pop());
				}
				if (ops.isEmpty()) {
					throw new IllegalArgumentException("Mismatched parentheses");
				}
				ops.pop();
			}
		}
		while (!ops.isEmpty()) {
			String op = ops.pop();
			if ("(".equals(op) || ")".equals(op)) {
				throw new IllegalArgumentException("Mismatched parentheses");
			}
			output.add(op);
		}
		return output;
	}

	private static double evalRpn(List<String> rpn) {
		Deque<Double> stack = new ArrayDeque<>();
		for (String token : rpn) {
			if (isNumber(token)) {
				stack.push(Double.parseDouble(token));
				continue;
			}
			if (stack.size() < 2) {
				throw new IllegalArgumentException("Bad expression");
			}
			double b = stack.pop();
			double a = stack.pop();
			stack.push(switch (token) {
				case "+" -> a + b;
				case "-" -> a - b;
				case "*" -> a * b;
				case "/" -> a / b;
				case "%" -> a % b;
				default -> throw new IllegalArgumentException("Unknown op " + token);
			});
		}
		if (stack.size() != 1) {
			throw new IllegalArgumentException("Bad expression");
		}
		return stack.pop();
	}

	private static boolean isNumber(String token) {
		return token.matches("[0-9]+(\\.[0-9]+)?");
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
