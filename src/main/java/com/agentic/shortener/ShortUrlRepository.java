package com.agentic.shortener;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShortUrlRepository extends JpaRepository<ShortUrlEntity, String> {

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update ShortUrlEntity e
			   set e.clicks = e.clicks + 1,
			       e.lastClickedAt = :now
			 where e.code = :code
			   and e.deleted = false
			   and (e.expiresAt is null or e.expiresAt > :now)
			""")
	int incrementClicksIfActive(@Param("code") String code, @Param("now") Instant now);
}
