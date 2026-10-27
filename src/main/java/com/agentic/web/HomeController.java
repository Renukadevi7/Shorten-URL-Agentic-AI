package com.agentic.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

	private final String apiKey;
	private final boolean bootstrapApiKey;

	public HomeController(
			@Value("${agentic.api-key:}") String apiKey,
			@Value("${agentic.ui.bootstrap-api-key:true}") boolean bootstrapApiKey) {
		this.apiKey = apiKey == null ? "" : apiKey.trim();
		this.bootstrapApiKey = bootstrapApiKey;
	}

	@GetMapping("/")
	public String home(Model model) {
		boolean configured = !apiKey.isBlank();
		model.addAttribute("apiKeyConfigured", configured);
		// Local control-plane convenience: inject the same runtime key the filter uses,
		// so the UI cannot drift from AGENTIC_API_KEY / application-local.properties.
		// No secret is committed; value comes only from the process environment.
		if (configured && bootstrapApiKey) {
			model.addAttribute("bootstrapApiKey", apiKey);
		}
		else {
			model.addAttribute("bootstrapApiKey", "");
		}
		return "index";
	}
}
