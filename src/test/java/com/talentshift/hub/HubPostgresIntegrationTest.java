package com.talentshift.hub;

import com.talentshift.hub.integration.importer.ImportService;
import com.talentshift.hub.integration.transfer.TransferService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class HubPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired ImportService imports; @Autowired JdbcTemplate db; @Autowired TransferService transfers;

    @Test void repeatedImportsKeepRawImmutableAndDoNotDuplicateCanonicalRecords(){
        imports.start("mock-system");imports.start("mock-system");
        assertThat(db.queryForObject("select count(*) from raw_source",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("select count(*) from raw_job",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("select count(*) from canonical_source",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from canonical_job",Integer.class)).isEqualTo(1);
    }
    @Test void concurrentImportsAreSerializedAndChecksumIdempotent() throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)){
            var tasks=java.util.List.of((Callable<UUID>)()->imports.start("mock-system"),(Callable<UUID>)()->imports.start("mock-system"));
            for(var f:executor.invokeAll(tasks))f.get(20,TimeUnit.SECONDS);
        }
        assertThat(db.queryForObject("select count(*) from raw_source",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("select count(*) from canonical_source",Integer.class)).isEqualTo(1);
    }
    @Test void transferEnqueueAndAttemptsAreIdempotent(){
        imports.start("mock-system");UUID job=db.queryForObject("select id from canonical_job limit 1",UUID.class);
        UUID first=transfers.enqueueJob(job),second=transfers.enqueueJob(job);
        assertThat(second).isEqualTo(first);assertThat(transfers.sendReady(10)).isEqualTo(1);assertThat(transfers.sendReady(10)).isZero();
        assertThat(db.queryForObject("select count(*) from transfer_attempt where outbox_id=?",Integer.class,first)).isEqualTo(1);
    }
}
