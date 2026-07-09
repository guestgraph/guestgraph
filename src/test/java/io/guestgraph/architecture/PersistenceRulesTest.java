package io.guestgraph.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.guestgraph.persistence.repo.TenantAgnostic;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;

/**
 * The persistence guardrails from research R1, enforced at build time:
 * tenant isolation must be reviewable in every query, and repository
 * scaffolding that bypasses it (CrudRepository&#38;co) is banned.
 */
class PersistenceRulesTest {

    static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.guestgraph");
    }

    @Test
    void noRepositoryScaffolding() {
        noClasses().should().beAssignableTo(CrudRepository.class)
                .orShould().beAssignableTo(PagingAndSortingRepository.class)
                .because("CrudRepository-style methods (findById without tenantId) bypass tenant scoping")
                .check(classes);
    }

    @Test
    void everyRepositoryMethodIsTenantScoped() {
        methods().that().areDeclaredInClassesThat().resideInAPackage("io.guestgraph.persistence.repo")
                .should(new ArchCondition<>("take a tenantId parameter or be @TenantAgnostic with a justification") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        if (method.getOwner().isAnnotation() || method.isAnnotatedWith(TenantAgnostic.class)) {
                            return;
                        }
                        boolean hasTenantId = Arrays.stream(method.reflect().getParameters())
                                .map(Parameter::getName)
                                .anyMatch("tenantId"::equals);
                        if (!hasTenantId) {
                            events.add(SimpleConditionEvent.violated(method,
                                    method.getFullName() + " has no tenantId parameter and no @TenantAgnostic"));
                        }
                    }
                })
                .because("a repository query without a tenant predicate is a cross-tenant data leak")
                .check(classes);
    }

    @Test
    void onlyPersistenceDependsOnJpa() {
        noClasses().that().resideOutsideOfPackage("io.guestgraph.persistence..")
                .should().dependOnClassesThat(new DescribedPredicate<>("belong to jakarta.persistence or Hibernate") {
                    @Override
                    public boolean test(com.tngtech.archunit.core.domain.JavaClass javaClass) {
                        String name = javaClass.getPackageName();
                        return name.startsWith("jakarta.persistence") || name.startsWith("org.hibernate");
                    }
                })
                .because("the engine and API are storage-agnostic; JPA is a persistence-internal detail")
                .check(classes);
    }
}
