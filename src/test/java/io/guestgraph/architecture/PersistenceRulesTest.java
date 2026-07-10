package io.guestgraph.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.guestgraph.config.LocalDevSeeder;
import io.guestgraph.persistence.repo.TenantAgnostic;
import io.guestgraph.resolution.TenantLock;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.Repository;

/**
 * The persistence guardrails from research R1, enforced at build time: tenant isolation must be
 * reviewable in every query, every query must be explicit (no derived methods, no ad-hoc
 * EntityManager queries), and repository scaffolding that bypasses it (CrudRepository&#38;co) is
 * banned — wherever the class lives, not just in the expected package.
 */
class PersistenceRulesTest {

  static JavaClasses appClasses;

  static final DescribedPredicate<JavaClass> SPRING_DATA_REPOSITORY =
      new DescribedPredicate<>("are Spring Data repositories") {
        @Override
        public boolean test(JavaClass javaClass) {
          return javaClass.isAssignableTo(Repository.class);
        }
      };

  @BeforeAll
  static void importClasses() {
    appClasses =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            // Spring AOT artifacts (Foo__BeanDefinitions, FooImpl__AotRepository) are
            // generated from the reviewed source — the rules target what humans write.
            .withImportOption(location -> !location.contains("__"))
            .importPackages("io.guestgraph");
  }

  @Test
  void noRepositoryScaffolding() {
    noClasses()
        .should()
        .beAssignableTo(CrudRepository.class)
        .orShould()
        .beAssignableTo(PagingAndSortingRepository.class)
        .because("CrudRepository-style methods (findById without tenantId) bypass tenant scoping")
        .check(appClasses);
  }

  @Test
  void everyRepositoryMethodIsTenantScoped() {
    // Scoped by type, not package: a Repository declared anywhere is held to the rule.
    methods()
        .that()
        .areDeclaredInClassesThat(SPRING_DATA_REPOSITORY)
        .should(
            new ArchCondition<>(
                "take a tenantId parameter or be @TenantAgnostic with a justification") {
              @Override
              public void check(JavaMethod method, ConditionEvents events) {
                if (method.isAnnotatedWith(TenantAgnostic.class)) {
                  return;
                }
                boolean hasTenantId =
                    Arrays.stream(method.reflect().getParameters())
                        .map(Parameter::getName)
                        .anyMatch("tenantId"::equals);
                if (!hasTenantId) {
                  events.add(
                      SimpleConditionEvent.violated(
                          method,
                          method.getFullName()
                              + " has no tenantId parameter and no @TenantAgnostic"));
                }
              }
            })
        .because("a repository query without a tenant predicate is a cross-tenant data leak")
        .check(appClasses);
  }

  @Test
  void everyRepositoryMethodDeclaresAnExplicitQuery() {
    methods()
        .that()
        .areDeclaredInClassesThat(SPRING_DATA_REPOSITORY)
        .should()
        .beAnnotatedWith(Query.class)
        .because(
            "R1: every repository method is explicit JPQL — a derived query method could carry"
                + " a decorative tenantId parameter that is never bound in the query")
        .check(appClasses);
  }

  @Test
  void noAdHocEntityManagerQueries() {
    classes()
        .should(
            new ArchCondition<>("not create ad-hoc EntityManager queries") {
              @Override
              public void check(JavaClass javaClass, ConditionEvents events) {
                javaClass.getMethodCallsFromSelf().stream()
                    .filter(
                        call ->
                            "jakarta.persistence.EntityManager"
                                    .equals(call.getTargetOwner().getName())
                                && call.getName().startsWith("create"))
                    .forEach(
                        call ->
                            events.add(
                                SimpleConditionEvent.violated(
                                    javaClass,
                                    call.getDescription()
                                        + " — EntityManager.create*Query bypasses the reviewed"
                                        + " @Query surface")));
              }
            })
        .because(
            "all query text lives in repository @Query annotations where tenant scoping is reviewable")
        .check(appClasses);
  }

  @Test
  void jdbcClientOnlyWhereJustified() {
    noClasses()
        .that()
        .doNotBelongToAnyOf(TenantLock.class, LocalDevSeeder.class)
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient")
        .because(
            "raw SQL escapes both the @Query guardrails and Hibernate flush coordination;"
                + " only the advisory lock and the local-dev seeder are exempt")
        .check(appClasses);
  }

  @Test
  void onlyPersistenceDependsOnJpa() {
    noClasses()
        .that()
        .resideOutsideOfPackage("io.guestgraph.persistence..")
        .should()
        .dependOnClassesThat(
            new DescribedPredicate<>("belong to jakarta.persistence or Hibernate") {
              @Override
              public boolean test(JavaClass javaClass) {
                String name = javaClass.getPackageName();
                return name.startsWith("jakarta.persistence") || name.startsWith("org.hibernate");
              }
            })
        .because("the engine and API are storage-agnostic; JPA is a persistence-internal detail")
        .check(appClasses);
  }
}
