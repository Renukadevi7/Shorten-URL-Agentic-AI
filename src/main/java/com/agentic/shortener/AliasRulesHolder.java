package com.agentic.shortener;

import org.springframework.stereotype.Component;

@Component
public class AliasRulesHolder {

	private volatile AliasRules active = AliasRules.defaults();

	public AliasRules get() {
		return active;
	}

	public void install(AliasRules rules) {
		this.active = rules == null ? AliasRules.defaults() : rules;
	}

	public void reset() {
		this.active = AliasRules.defaults();
	}
}
