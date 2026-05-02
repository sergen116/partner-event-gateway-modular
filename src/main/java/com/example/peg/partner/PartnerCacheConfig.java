package com.example.peg.partner;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Optional;

@Configuration
class PartnerCacheConfig {

    @Bean
    Cache<String, Optional<Partner>> partnerCache(MeterRegistry meters) {
        Cache<String, Optional<Partner>> cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(60))
                .maximumSize(10_000)
                .recordStats()
                .build();

        meters.gauge("peg.partner_cache.size", cache, Cache::estimatedSize);
        meters.gauge("peg.partner_cache.hit_ratio", cache,
                c -> c.stats().requestCount() == 0 ? 0.0 : c.stats().hitRate());
        return cache;
    }
}
