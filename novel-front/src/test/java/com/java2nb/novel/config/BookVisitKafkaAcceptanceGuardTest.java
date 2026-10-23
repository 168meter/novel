package com.java2nb.novel.config;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class BookVisitKafkaAcceptanceGuardTest {
    final String run = "20261022220000000_ab12cd34";
    final String identity = "20261017233540699";
    @TempDir Path temporary;

    @Test void acceptsOnlyFreshTestNamespace() {
        var names = new BookVisitKafkaAcceptance.Names(run, identity);
        assertThat(names.schema()).startsWith("cv_it_" + identity + "_kv_").hasSizeLessThan(65);
        assertThat(names.topics()).hasSize(6).doesNotHaveDuplicates();
        assertThat(names.groups()).hasSize(3).doesNotHaveDuplicates();
        names.checkLedger(Map.of("run",run,"schema",names.schema(),"topics",names.topics(),
            "groups",names.groups(),"preexistingAbsent",true));
    }
    @Test void rejectsExistingBusinessNames() {
        assertThatThrownBy(() -> new BookVisitKafkaAcceptance.Names("joint-20261020152352233", identity))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BookVisitKafkaAcceptance.Names(run, "novel_ab"))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsSqlAndPathCharacters() {
        for (String bad : new String[]{"../abc", run + ";DROP", run.toUpperCase(), ""})
            assertThatThrownBy(() -> new BookVisitKafkaAcceptance.Names(bad, identity))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cleanupRejectsForeignTopicAndSchema() {
        var names = new BookVisitKafkaAcceptance.Names(run, identity);
        assertThatThrownBy(() -> names.checkLedger(Map.of("run",run,"schema","novel_ab",
            "topics", names.topics(),"groups",names.groups(),"preexistingAbsent",true))) .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> names.checkLedger(Map.of("run",run,"schema",names.schema(),
            "topics",java.util.List.of("joint-visit"),"groups",names.groups(),"preexistingAbsent",true)))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cleanupRequiresMatchingRun() {
        var names = new BookVisitKafkaAcceptance.Names(run, identity);
        assertThatThrownBy(() -> names.checkLedger(Map.of("run","other","schema",names.schema(),
            "topics",names.topics(),"groups",names.groups(),"preexistingAbsent",true))) .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cleanupRequiresProofOfPriorAbsence() {
        var names = new BookVisitKafkaAcceptance.Names(run, identity);
        assertThatThrownBy(() -> names.checkLedger(Map.of("run",run,"schema",names.schema(),
            "topics",names.topics(),"groups",names.groups()))) .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void actualMapperXmlAndTransactionalWriterLoadOffline() throws Exception {
        var jdbc = fixture();
        var fault = new BookVisitKafkaAcceptance.WriteFault(false,jdbc);
        var writer = BookVisitKafkaAcceptance.writer(jdbc,fault);
        writer.write(Map.of(31L,2L,32L,1L));
        assertThat(jdbc.queryForObject("SELECT SUM(visit_count) FROM book",Long.class)).isEqualTo(3);
        assertThat(fault.successes).isEqualTo(2);
        assertThat(fault.sawTransaction).isTrue();
    }
    @Test void faultGateReallyRollsBackProductionMappedUpdateAndCanRecover() throws Exception {
        var jdbc = fixture();
        var fault = new BookVisitKafkaAcceptance.WriteFault(true,jdbc);
        var writer = BookVisitKafkaAcceptance.writer(jdbc,fault);
        assertThatThrownBy(() -> writer.write(Map.of(31L,1L)))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(fault.afterUpdate.stream().mapToLong(r -> ((Number)r.get("visit_count")).longValue()).sum()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT SUM(visit_count) FROM book",Long.class)).isZero();
        fault.release.set(true);
        writer.write(Map.of(31L,2L,32L,1L));
        assertThat(jdbc.queryForObject("SELECT SUM(visit_count) FROM book",Long.class)).isEqualTo(3);
    }
    JdbcTemplate fixture() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:kv_"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE book (id BIGINT PRIMARY KEY,visit_count BIGINT NOT NULL)");
        jdbc.update("INSERT INTO book VALUES (31,0),(32,0)");
        return jdbc;
    }
    @Test void rollbackEvidenceAcceptsEitherAggregatedBookUpdateFirst() throws Exception {
        for (boolean twoVisitBookFirst : new boolean[]{true,false}) {
            var jdbc = fixture();
            var fault = new BookVisitKafkaAcceptance.WriteFault(true,jdbc);
            var writer = BookVisitKafkaAcceptance.writer(jdbc,fault);
            var totals = new java.util.LinkedHashMap<Long,Long>();
            if (twoVisitBookFirst) {totals.put(31L,2L);totals.put(32L,1L);}
            else {totals.put(32L,1L);totals.put(31L,2L);}
            assertThatThrownBy(() -> writer.write(totals)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            fault.verifyObservedUpdate(totals);
            assertThat(fault.injectedUpdate).containsEntry("bookId",twoVisitBookFirst ? 31L : 32L)
                .containsEntry("delta",twoVisitBookFirst ? 2L : 1L).containsEntry("affectedRows",1L);
            assertThat(jdbc.queryForObject("SELECT SUM(visit_count) FROM book",Long.class)).isZero();
            fault.release.set(true);
            writer.write(totals);
            assertThat(jdbc.queryForObject("SELECT visit_count FROM book WHERE id=31",Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT visit_count FROM book WHERE id=32",Long.class)).isEqualTo(1);
        }
    }
    @Test void rollbackEvidenceRejectsMissingOrIncorrectObservedUpdate() throws Exception {
        var jdbc = fixture();
        var fault = new BookVisitKafkaAcceptance.WriteFault(true,jdbc);
        var writer = BookVisitKafkaAcceptance.writer(jdbc,fault);
        var totals = new java.util.LinkedHashMap<Long,Long>();totals.put(31L,2L);totals.put(32L,1L);
        assertThatThrownBy(() -> fault.verifyObservedUpdate(totals)).hasMessageContaining("metadata missing");
        assertThatThrownBy(() -> writer.write(totals)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        fault.afterUpdate = java.util.List.of(Map.of("id",31L,"visit_count",1L),Map.of("id",32L,"visit_count",0L));
        assertThatThrownBy(() -> fault.verifyObservedUpdate(totals)).hasMessageContaining("snapshot must match exactly");
    }
    @Test void brokerFailureDoesNotSuppressOwnedSchemaCleanup() throws Exception {
        var acceptance = cleanupFixture();
        doThrow(new IllegalStateException("TEST Broker unavailable")).when(acceptance).connectBroker();
        assertThatThrownBy(acceptance::cleanup).hasMessageContaining("Cleanup incomplete");
        verify(acceptance.server).execute("DROP DATABASE `"+acceptance.names.schema()+"`");
        assertThat(acceptance.result).containsEntry("cleanup","FAILED");
    }
    @Test void clusterMismatchNeverDeletesKafkaResourcesButStillCleansOwnedSchema() throws Exception {
        var acceptance = cleanupFixture();
        acceptance.admin = mock(org.apache.kafka.clients.admin.Admin.class);
        acceptance.clusterId = "OTHER_CLUSTER";
        assertThatThrownBy(acceptance::cleanup).hasMessageContaining("Cleanup incomplete");
        verifyNoInteractions(acceptance.admin);
        verify(acceptance.server).execute("DROP DATABASE `"+acceptance.names.schema()+"`");
    }
    BookVisitKafkaAcceptance cleanupFixture() throws Exception {
        var database = mock(JdbcTemplate.class);
        var env = Map.of("KVI_RUN",run,"KVI_IDENTITY",identity,"KVI_USER","cv_"+identity,
            "KVI_PASSWORD","test-not-a-secret","KVI_EVIDENCE",temporary.toAbsolutePath().toString());
        var acceptance = spy(new BookVisitKafkaAcceptance(env,database));
        var names = acceptance.names;
        when(database.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,names.schema())).thenReturn(1,0);
        when(database.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name='acceptance_owner'",Integer.class,names.schema())).thenReturn(1);
        when(database.queryForObject("SELECT run_id FROM `"+names.schema()+"`.acceptance_owner",String.class)).thenReturn(run);
        acceptance.save("ownership.json",Map.of("run",run,"schema",names.schema(),"topics",names.topics(),
            "groups",names.groups(),"preexistingAbsent",true,"clusterId","EXPECTED_CLUSTER"));
        var reread = BookVisitKafkaAcceptance.JSON.readValue(java.nio.file.Files.readString(temporary.resolve("ownership.json")),
            new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        assertThat(reread).containsEntry("run",run).containsEntry("schema",names.schema())
            .containsEntry("topics",names.topics()).containsEntry("groups",names.groups()).containsEntry("preexistingAbsent",true);
        return acceptance;
    }
}
