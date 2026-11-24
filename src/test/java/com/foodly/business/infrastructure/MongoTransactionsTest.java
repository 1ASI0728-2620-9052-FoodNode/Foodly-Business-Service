package com.foodly.business.infrastructure;

import com.foodly.business.application.*;
import com.foodly.business.application.port.EventOutbox;
import com.foodly.business.domain.model.*;
import com.foodly.business.infrastructure.persistence.*;
import com.foodly.business.infrastructure.messaging.*;
import com.foodly.business.application.port.ImageStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.foodly.business.Fixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Opt-in integration tests against a disposable real MongoDB replica set, never the user's database. */
@EnabledIfEnvironmentVariable(named="FOODLY_MONGOD", matches=".+")
@SpringBootTest(properties={"business.outbox.enabled=false", "business.iam.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "business.iam.issuer=foodly-iam", "business.iam.audience=business", "spring.rabbitmq.username=test", "spring.rabbitmq.password=test"})
@DirtiesContext
class MongoTransactionsTest {
    private static final String REPLICA_SET = "rs0";
    static Process process;
    @Autowired HuariqueApplicationService huariques;
    @Autowired MenuApplicationService menus;
    @Autowired BusinessRepository repository;
    @Autowired MongoTemplate mongo;
    @Autowired ObjectMapper json;
    @MockitoSpyBean MongoEventOutbox outbox;

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) throws Exception {
        Path executable = Path.of(System.getenv("FOODLY_MONGOD")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable))
            throw new IllegalArgumentException("FOODLY_MONGOD must be the path to a runnable mongod executable, not a MongoDB URI: " + executable);
        int port; try (var socket=new ServerSocket(0)) { port=socket.getLocalPort(); }
        Path path=Files.createDirectories(Path.of("target","mongo-it-"+UUID.randomUUID()).toAbsolutePath());
        process=new ProcessBuilder(executable.toString(),"--dbpath",path.toString(),"--port",Integer.toString(port),
                "--bind_ip","127.0.0.1","--replSet",REPLICA_SET,"--logpath",path.resolve("mongod.log").toString()).redirectErrorStream(true).redirectOutput(path.resolve("process.log").toFile()).start();
        Runtime.getRuntime().addShutdownHook(new Thread(()->process.destroy()));
        try (var client=MongoClients.create("mongodb://127.0.0.1:"+port+"/?directConnection=true&serverSelectionTimeoutMS=1000")) {
            var db=client.getDatabase("admin"); boolean ready=false;
            for(int i=0;i<20;i++) {
                try { db.runCommand(new Document("ping",1)); ready=true; break; }
                catch (RuntimeException e) { Thread.sleep(200); }
            }
            if(!ready) throw new IllegalStateException("Disposable MongoDB failed to start; inspect " + path);
            db.runCommand(new Document("replSetInitiate",new Document("_id",REPLICA_SET).append("members",List.of(new Document("_id",0).append("host","127.0.0.1:"+port)))));
            ready=false;
            for(int i=0;i<100;i++) {
                if(Boolean.TRUE.equals(db.runCommand(new Document("hello",1)).getBoolean("isWritablePrimary"))) { ready=true; break; }
                Thread.sleep(200);
            }
            if(!ready) throw new IllegalStateException("Test replica set did not elect a primary");
        } catch (Exception e) { process.destroyForcibly(); throw e; }
        String uri="mongodb://127.0.0.1:"+port+"/foodly_test?replicaSet="+REPLICA_SET+"&serverSelectionTimeoutMS=3000";
        properties.add("spring.data.mongodb.uri",()->uri);
    }
    @AfterAll static void stop() throws Exception { if(process!=null) { process.destroy(); if(!process.waitFor(10,TimeUnit.SECONDS)) process.destroyForcibly().waitFor(); } }
    @BeforeEach void clean() { reset(outbox); mongo.remove(new Query(),HuariqueDocument.class); mongo.remove(new Query(),MenuDocument.class); mongo.remove(new Query(),OutboxDocument.class); }
    @Test void writesProfileAndEventAtomicallyAndMapsEmbeddedValues() {
        var hello=mongo.executeCommand(new Document("hello",1));
        assertThat(hello.getString("setName")).isEqualTo(REPLICA_SET);
        assertThat(hello.getBoolean("isWritablePrimary")).isTrue();
        assertThat(hello.getList("hosts",String.class)).hasSize(1);
        long committedBefore=transactionCount("totalCommitted");
        var h=huariques.register("owner",profile()); assertThat(h.version()).isZero();
        assertThat(huariques.get(h.id())).isEqualTo(h);
        assertThat(mongo.count(new Query(),OutboxDocument.class)).isEqualTo(1);
        var event=mongo.findOne(new Query(),OutboxDocument.class);
        assertThat(event.huariqueId()).isEqualTo(h.id());
        assertThat(event.type()).isEqualTo("HuariqueRegistrado");
        assertThat(transactionCount("totalCommitted")).isGreaterThan(committedBefore);
        Document raw=mongo.getCollection("huariques").find().first();
        assertThat(raw.get("priceRange",Document.class).get("minimum")).isInstanceOf(org.bson.types.Decimal128.class);
        assertThat(raw.get("location",Document.class).getDouble("latitude")).isEqualTo(-12);
    }
    @Test void outboxFailureRollsBackProfile() {
        long abortedBefore=transactionCount("totalAborted");
        doAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            invocation.callRealMethod(); // Persist the real event in the same transaction before forcing its rollback.
            assertThat(mongo.count(new Query(),HuariqueDocument.class)).isEqualTo(1);
            assertThat(mongo.count(new Query(),OutboxDocument.class)).isEqualTo(1);
            throw new IllegalStateException("injected failure after real outbox write");
        }).when(outbox).append(any(EventOutbox.Event.class));
        assertThatThrownBy(()->huariques.register("owner",profile())).isInstanceOf(IllegalStateException.class);
        assertThat(repository.findByOwnerId("owner")).isEmpty(); assertThat(mongo.count(new Query(),OutboxDocument.class)).isZero();
        assertThat(transactionCount("totalAborted")).isGreaterThan(abortedBefore);
    }
    @Test void databaseUniqueIndexProtectsOwnerWhenDomainCheckIsBypassed() {
        huariques.register("owner",profile());
        var h=huarique();
        var candidate=new Huarique(h.id(),h.ownerId(),h.name(),h.address(),h.cuisineType(),h.priceRange(),h.location(),h.openStatus(),h.tags(),h.openingHours(),h.images(),null);
        assertThatThrownBy(()->repository.save(candidate)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
    @Test void optimisticLockPreventsConcurrentOverwrite() {
        var original=huariques.register("owner",profile());
        var updated=huariques.changeStatus(original.id(),"owner",OpenStatus.OPEN,original.version()); assertThat(updated.version()).isEqualTo(1L);
        assertThatThrownBy(()->repository.save(original.changeOpenStatus(OpenStatus.CLOSED))).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        assertThat(huariques.get(original.id()).openStatus()).isEqualTo(OpenStatus.OPEN);
    }
    @Test void persistsNestedMenuAndRemovesDishesWithVersionCheck() {
        var h=huariques.register("owner",profile()); var m=menus.replace(h.id(),"owner",menu().categories(),null);
        assertThat(menus.get(h.id())).isEqualTo(m); assertThat(m.version()).isZero();
        var changed=menus.replace(h.id(),"owner",List.of(),m.version()); assertThat(changed.version()).isEqualTo(1L);
        assertThat(menus.get(h.id()).categories()).isEmpty(); assertThat(mongo.count(new Query(),OutboxDocument.class)).isEqualTo(3);
    }
    @Test void outboxRetriesSameEventAfterDeliveryFailure() {
        huariques.register("owner",profile()); var publisher=mock(BusinessEventPublisher.class);
        var worker=new OutboxPublisher(mongo,publisher,mock(ImageStorage.class),json);
        doThrow(new IllegalStateException("broker unavailable")).when(publisher).publish(any()); worker.drain();
        var pending=mongo.findOne(new Query(),OutboxDocument.class);
        assertThat(pending.publishedAt()).isNull(); assertThat(pending.attempts()).isEqualTo(1);
        mongo.updateFirst(new Query(),new org.springframework.data.mongodb.core.query.Update().set("nextAttemptAt",java.time.Instant.EPOCH),OutboxDocument.class);
        reset(publisher); worker.drain();
        var delivered=mongo.findById(pending.id(),OutboxDocument.class);
        assertThat(delivered.publishedAt()).isNotNull(); assertThat(delivered.attempts()).isEqualTo(2); assertThat(delivered.payload()).isEqualTo(pending.payload());
        verify(publisher).publish(argThat(e->e.id().equals(pending.id())));
    }
    @Test void imageMetadataAndDeletionTaskCommitTogether() {
        var h=huariques.register("owner",profile()); var image=image();
        var added=huariques.changeImage(h.id(),"owner",null,image); assertThat(huariques.get(h.id()).images()).containsExactly(image);
        huariques.changeImage(h.id(),"owner",image.id(),null); assertThat(huariques.get(h.id()).images()).isEmpty();
        var storage=mock(ImageStorage.class); var publisher=mock(BusinessEventPublisher.class);
        new OutboxPublisher(mongo,publisher,storage,json).drain();
        verify(storage).delete(image.publicId()); verify(publisher,times(3)).publish(any());
        assertThat(mongo.findAll(OutboxDocument.class)).allMatch(e->e.publishedAt()!=null);
    }
    @Test void openingHoursRoundTrip() {
        var p=profile(); var hours=List.of(new OpeningHours(java.time.DayOfWeek.MONDAY,java.time.LocalTime.of(9,30),java.time.LocalTime.of(18,0)));
        var h=huariques.register("owner",new ProfileData(p.name(),p.address(),p.cuisineType(),p.priceRange(),p.location(),p.tags(),hours));
        assertThat(huariques.get(h.id()).openingHours()).containsExactlyElementsOf(hours);
    }
    private long transactionCount(String counter) {
        var status=mongo.getMongoDatabaseFactory().getMongoDatabase("admin").runCommand(new Document("serverStatus",1));
        return ((Number)status.get("transactions",Document.class).get(counter)).longValue();
    }
}
