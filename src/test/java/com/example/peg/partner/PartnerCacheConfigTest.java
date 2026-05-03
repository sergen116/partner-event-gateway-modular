package com.example.peg.partner;

import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PartnerCacheConfigTest {

    @Test
    void partnerCache_storesEntriesAndRegistersGauges() {
        MeterRegistry meters = new SimpleMeterRegistry();
        Cache<String, Optional<Partner>> cache = new PartnerCacheConfig().partnerCache(meters);

        Partner partner = new Partner("p", "hash", null, null, true);
        cache.put("p", Optional.of(partner));

        assertThat(cache.getIfPresent("p")).contains(partner);
        // Gauges registered by config
        assertThat(meters.find("peg.partner_cache.size").gauge()).isNotNull();
        assertThat(meters.find("peg.partner_cache.hit_ratio").gauge()).isNotNull();
    }

    @Test
    void hitRatioGauge_zeroWhenNoRequests() {
        MeterRegistry meters = new SimpleMeterRegistry();
        new PartnerCacheConfig().partnerCache(meters);
        assertThat(meters.find("peg.partner_cache.hit_ratio").gauge().value()).isEqualTo(0.0);
    }
}
