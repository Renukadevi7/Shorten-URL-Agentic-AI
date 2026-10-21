package com.agentic.agent.tools;

import org.springframework.stereotype.Component;

import com.agentic.agent.AgentTool;
import com.agentic.agent.ToolResult;

@Component
public class WeatherMockTool implements AgentTool {

	private static final int[] TEMPS = { 14, 18, 21, 24, 27, 11, 16 };
	private static final String[] CONDITIONS = { "clear", "partly cloudy", "overcast", "light rain", "breezy" };

	@Override
	public String name() {
		return "weather_mock";
	}

	@Override
	public String description() {
		return "Return mocked weather for a city name.";
	}

	@Override
	public ToolResult run(String city) {
		sleep(300);
		String name = (city == null || city.isBlank()) ? "your city" : city.trim();
		int seed = 0;
		for (int i = 0; i < name.length(); i++) {
			seed += name.charAt(i);
		}
		int temp = TEMPS[Math.floorMod(seed, TEMPS.length)];
		String condition = CONDITIONS[Math.floorMod(seed, CONDITIONS.length)];
		return ToolResult.success(name + ": " + temp + "°C, " + condition + ". (Mock data for the demo agent.)");
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
