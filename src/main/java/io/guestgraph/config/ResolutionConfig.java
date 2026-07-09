package io.guestgraph.config;

import io.guestgraph.resolution.DeterministicMatcher;
import io.guestgraph.resolution.GraphPort;
import io.guestgraph.resolution.ResolutionEngine;
import io.guestgraph.resolution.ResolutionStrategy;
import io.guestgraph.survivorship.GoldenProfileDeriver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ResolutionConfig {

    @Bean
    public GoldenProfileDeriver goldenProfileDeriver() {
        return new GoldenProfileDeriver();
    }

    @Bean
    public ResolutionStrategy resolutionStrategy() {
        return new DeterministicMatcher();
    }

    @Bean
    public ResolutionEngine resolutionEngine(GraphPort graph, ResolutionStrategy strategy,
            GoldenProfileDeriver profileDeriver) {
        return new ResolutionEngine(graph, strategy, profileDeriver);
    }
}
