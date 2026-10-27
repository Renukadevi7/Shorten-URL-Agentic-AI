package com.agentic.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilterConfig {

	@Bean
	ApiKeyAuthFilter apiKeyAuthFilter(@Value("${agentic.api-key:}") String apiKey) {
		return new ApiKeyAuthFilter(apiKey);
	}

	@Bean
	RateLimitFilter rateLimitFilter(
			@Value("${agentic.rate-limit.requests-per-minute:60}") int limit) {
		return new RateLimitFilter(limit);
	}

	@Bean
	FilterRegistrationBean<ApiKeyAuthFilter> apiKeyFilterRegistration(ApiKeyAuthFilter filter) {
		FilterRegistrationBean<ApiKeyAuthFilter> bean = new FilterRegistrationBean<>();
		bean.setFilter(filter);
		bean.addUrlPatterns("/*");
		bean.setOrder(1);
		return bean;
	}

	@Bean
	FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
		FilterRegistrationBean<RateLimitFilter> bean = new FilterRegistrationBean<>();
		bean.setFilter(filter);
		bean.addUrlPatterns("/api/*");
		bean.setOrder(2);
		return bean;
	}
}
