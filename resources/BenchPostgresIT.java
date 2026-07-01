package org.springframework.samples.petclinic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.samples.petclinic.model.Owner;
import org.springframework.samples.petclinic.repository.OwnerRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// Injected by the benchmark harness (vars/benchStages.groovy Checkout stage) —
// not part of the upstream spring-petclinic-rest repo, which ships no
// Testcontainers/failsafe integration tests of its own. Named *IT rather than
// *Test so Surefire's default include pattern skips it during the
// unit-test-${i} stage; the integration-test-${i} stage runs it explicitly
// via `-Dtest=BenchPostgresIT`.
@SpringBootTest
@ActiveProfiles("spring-data-jpa")
@Testcontainers
class BenchPostgresIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private OwnerRepository ownerRepository;

    @Test
    void savesAndReloadsOwnerFromRealPostgresContainer() {
        Owner owner = new Owner();
        owner.setFirstName("Bench");
        owner.setLastName("Tester");
        owner.setAddress("123 Storage Ave");
        owner.setCity("BenchCity");
        owner.setTelephone("5551234567");
        ownerRepository.save(owner);

        assertThat(ownerRepository.findAll())
                .extracting(Owner::getLastName)
                .contains("Tester");
    }
}
