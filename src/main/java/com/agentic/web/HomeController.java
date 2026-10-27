package com.agentic.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

	private final boolean apiKeyConfigured;

	public HomeController(@Value("${agentic.api-key:}") String apiKey) {
		this.apiKeyConfigured = apiKey != null && !apiKey.isBlank();
	}

	@GetMapping("/")
	public String home(Model model) {
		// Never embed the privileged API key in HTML/JS. Operator pastes locally.
		model.addAttribute("apiKeyConfigured", apiKeyConfigured);
		model.addAttribute("bootstrapApiKey", "");
		return "index";
	}
}
