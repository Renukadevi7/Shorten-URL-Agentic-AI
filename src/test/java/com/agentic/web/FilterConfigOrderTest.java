package com.agentic.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

@SpringBootTest(properties = "agentic.api-key=local-test-key-16c")
class FilterConfigOrderTest {

	@Autowired
	private FilterRegistrationBean<RateLimitFilter> rateLimitRegistration;

	@Autowired
	private FilterRegistrationBean<ApiKeyAuthFilter> apiKeyRegistration;

	@Test
	void rateLimitFilterIsRegisteredBeforeAuth() {
		assertTrue(rateLimitRegistration.getOrder() < apiKeyRegistration.getOrder(),
				"rate limit order=" + rateLimitRegistration.getOrder()
						+ " must be before auth order=" + apiKeyRegistration.getOrder());
	}
}
