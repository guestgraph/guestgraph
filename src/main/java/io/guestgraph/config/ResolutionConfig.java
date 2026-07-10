package io.guestgraph.config;

import io.guestgraph.resolution.CompositeStrategy;
import io.guestgraph.resolution.DeterministicMatcher;
import io.guestgraph.resolution.ExplainOperation;
import io.guestgraph.resolution.FuzzyMatcher;
import io.guestgraph.resolution.GraphPort;
import io.guestgraph.resolution.MatchingPolicy;
import io.guestgraph.resolution.ResolutionEngine;
import io.guestgraph.resolution.ResolutionStrategy;
import io.guestgraph.resolution.ReviewDecisionOperation;
import io.guestgraph.resolution.UnmergeOperation;
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
  public ResolutionStrategy resolutionStrategy(GraphPort graph) {
    return new CompositeStrategy(
        new DeterministicMatcher(),
        new FuzzyMatcher(),
        new MatchingPolicy(),
        graph::matchingConfig);
  }

  @Bean
  public ResolutionEngine resolutionEngine(
      GraphPort graph, ResolutionStrategy strategy, GoldenProfileDeriver profileDeriver) {
    return new ResolutionEngine(graph, strategy, profileDeriver);
  }

  @Bean
  public ExplainOperation explainOperation(GraphPort graph) {
    return new ExplainOperation(graph);
  }

  @Bean
  public UnmergeOperation unmergeOperation(GraphPort graph, ResolutionEngine engine) {
    return new UnmergeOperation(graph, engine);
  }

  @Bean
  public ReviewDecisionOperation reviewDecisionOperation(GraphPort graph, ResolutionEngine engine) {
    return new ReviewDecisionOperation(graph, engine);
  }
}
