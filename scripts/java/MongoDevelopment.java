package com.foodly.business.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodly.business.application.HuariqueApplicationService;
import com.foodly.business.application.MenuApplicationService;
import com.foodly.business.application.ProfileData;
import com.foodly.business.domain.model.*;
import com.foodly.business.infrastructure.config.BusinessConfiguration;
import com.foodly.business.infrastructure.persistence.*;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoSocketException;
import com.mongodb.client.*;
import org.bson.Document;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/** Native development tooling, deliberately outside the production source tree. Never targets port 27017. */
public class MongoDevelopment {
    private static final String HOST = "127.0.0.1:27018";
    private static final String URI = "mongodb://" + HOST + "/foodly_business?replicaSet=rs0";
    private static final String CONTROL_URI = "mongodb://" + HOST
            + "/admin?directConnection=true&serverSelectionTimeoutMS=1500&connectTimeoutMS=1500&socketTimeoutMS=5000";

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !Set.of("init", "status", "stop", "verify").contains(args[0]))
            throw new IllegalArgumentException("Expected init|status|stop|verify and the managed dbPath");
        Path expectedPath = Path.of(args[1]).toRealPath();
        try (var client = MongoClients.create(CONTROL_URI)) {
            var admin = client.getDatabase("admin");
            waitForServer(admin);
            assertManagedProcess(admin, expectedPath);
            if (args[0].equals("init")) initialize(admin);
            assertReplicaSet(admin);
            if (args[0].equals("stop")) {
                try { admin.runCommand(new Document("shutdown", 1).append("force", false).append("timeoutSecs", 10)); }
                catch (MongoSocketException expectedDisconnect) { /* shutdown closes the connection before replying */ }
                return;
            }
            if (args[0].equals("verify")) verifyPersistence();
            var hello = admin.runCommand(new Document("hello", 1));
            System.out.println("MONGODB_DEVELOPMENT=" + new Document("replicaSet", hello.getString("setName"))
                    .append("primary", hello.getBoolean("isWritablePrimary"))
                    .append("members", hello.getList("hosts", String.class))
                    .append("dataPath", expectedPath.toString()).append("uri", URI)
                    .append("retainedVerificationRecords", retainedVerificationRecords(client)).toJson());
        }
    }

    private static void waitForServer(MongoDatabase admin) throws InterruptedException {
        for (int attempt = 0; attempt < 15; attempt++) {
            try { admin.runCommand(new Document("ping", 1)); return; }
            catch (com.mongodb.MongoException unavailable) { if (attempt == 14) throw unavailable; Thread.sleep(200); }
        }
    }

    private static List<Document> retainedVerificationRecords(MongoClient client) {
        var database = client.getDatabase("foodly_business");
        var result = new ArrayList<Document>();
        var filter = new Document("ownerId", new Document("$regex", "^local-verification-"));
        for (var h : database.getCollection("huariques").find(filter).limit(10)) {
            var event = database.getCollection("business_outbox").find(new Document("huariqueId", h.get("_id"))).first();
            if (event != null) {
                var payload = Document.parse(event.getString("payload"));
                result.add(new Document("huariqueId", payload.get("data", Document.class).getString("id"))
                        .append("eventId", payload.getString("eventId")).append("published", event.get("publishedAt") != null));
            }
        }
        return result;
    }

    private static void assertManagedProcess(MongoDatabase admin, Path expectedPath) throws Exception {
        var parsed = admin.runCommand(new Document("getCmdLineOpts", 1)).get("parsed", Document.class);
        var actualPath = Path.of(parsed.get("storage", Document.class).getString("dbPath")).toRealPath();
        var network = parsed.get("net", Document.class);
        if (!actualPath.equals(expectedPath) || network.getInteger("port") != 27018
                || !"127.0.0.1".equals(network.getString("bindIp")))
            throw new IllegalStateException("Refusing to modify a process with a different dbPath, port or bind address");
    }

    private static void initialize(MongoDatabase admin) {
        try { admin.runCommand(new Document("replSetGetConfig", 1)); }
        catch (MongoCommandException notInitialized) {
            if (notInitialized.getErrorCode() != 94) throw notInitialized;
            admin.runCommand(new Document("replSetInitiate", new Document("_id", "rs0")
                    .append("members", List.of(new Document("_id", 0).append("host", HOST)))));
        }
    }

    private static void assertReplicaSet(MongoDatabase admin) throws InterruptedException {
        var config = admin.runCommand(new Document("replSetGetConfig", 1)).get("config", Document.class);
        var members = config.getList("members", Document.class);
        if (!"rs0".equals(config.getString("_id")) || members.size() != 1 || !HOST.equals(members.get(0).getString("host")))
            throw new IllegalStateException("Unexpected replica set configuration. No automatic reconfiguration is permitted");
        for (int attempt = 0; attempt < 60; attempt++) {
            var hello = admin.runCommand(new Document("hello", 1));
            if ("rs0".equals(hello.getString("setName")) && Boolean.TRUE.equals(hello.getBoolean("isWritablePrimary"))
                    && hello.getList("hosts", String.class).equals(List.of(HOST))) return;
            Thread.sleep(500);
        }
        throw new IllegalStateException("rs0 did not become a writable single-node primary");
    }

    // This is a Spring Boot persistence probe, not an alternate HTTP server or IAM/RabbitMQ implementation.
    // It uses the real application services, MongoDB adapters, transaction manager and Outbox persistence.
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @ImportAutoConfiguration({MongoAutoConfiguration.class, MongoDataAutoConfiguration.class, JacksonAutoConfiguration.class})
    @Import({BusinessConfiguration.class, BusinessRepository.class, MongoEventOutbox.class,
            HuariqueApplicationService.class, MenuApplicationService.class})
    static class PersistenceConfiguration { }

    private static void verifyPersistence() throws Exception {
        if (!URI.equals(System.getenv("MONGODB_URI")))
            throw new IllegalStateException("The probe requires the exact managed MONGODB_URI on port 27018");
        try (var context = new SpringApplicationBuilder(PersistenceConfiguration.class).web(WebApplicationType.NONE).run()) {
            var mongo = context.getBean(MongoTemplate.class);
            var service = context.getBean(HuariqueApplicationService.class);
            var json = context.getBean(ObjectMapper.class);
            if (!"foodly_business".equals(mongo.getDb().getName())) throw new IllegalStateException("Unexpected database");
            var data = new ProfileData("Foodly local verification", "Local development verification", CuisineType.OTHER,
                    new PriceRange(BigDecimal.ZERO, BigDecimal.TEN), new GeoCoordinates(-12.0464, -77.0428), List.of(), List.of());
            long commitsBefore = transactionCount(mongo, "totalCommitted");
            var h = service.register("local-verification-" + UUID.randomUUID(), data);
            var eventQuery = Query.query(where("huariqueId").is(h.id()).and("type").is("HuariqueRegistrado"));
            var event = mongo.findOne(eventQuery, OutboxDocument.class);
            if (!service.get(h.id()).equals(h) || event == null || !json.readTree(event.payload()).path("data").path("id").asText().equals(h.id().toString()))
                throw new IllegalStateException("Committed huarique and matching Outbox event were not both persisted");
            long commitsAfter = transactionCount(mongo, "totalCommitted");
            if (commitsAfter <= commitsBefore) throw new IllegalStateException("MongoDB did not report a committed transaction");

            long abortsBefore = transactionCount(mongo, "totalAborted");
            var rolledBackId = new AtomicReference<UUID>();
            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            try {
                transaction.execute(status -> {
                    var candidate = service.register("local-rollback-" + UUID.randomUUID(), data);
                    rolledBackId.set(candidate.id());
                    if (!mongo.exists(Query.query(where("id").is(candidate.id())), HuariqueDocument.class)
                            || !mongo.exists(Query.query(where("huariqueId").is(candidate.id())), OutboxDocument.class))
                        throw new IllegalStateException("Both real writes must exist inside the transaction before rollback");
                    throw new ExpectedRollback();
                });
                throw new IllegalStateException("Expected transaction rollback did not occur");
            } catch (ExpectedRollback expected) { /* deliberate application exception after both writes */ }
            long abortsAfter = transactionCount(mongo, "totalAborted");
            if (rolledBackId.get() == null || mongo.exists(Query.query(where("id").is(rolledBackId.get())), HuariqueDocument.class)
                    || mongo.exists(Query.query(where("huariqueId").is(rolledBackId.get())), OutboxDocument.class)
                    || abortsAfter <= abortsBefore) throw new IllegalStateException("MongoDB did not roll back both real writes");
            System.out.println("SPRING_BOOT_PERSISTENCE=" + new Document("uri", URI).append("verifiedAt", Instant.now().toString())
                    .append("huariqueId", h.id().toString()).append("eventId", event.id().toString())
                    .append("commitsBefore", commitsBefore).append("commitsAfter", commitsAfter)
                    .append("abortsBefore", abortsBefore).append("abortsAfter", abortsAfter)
                    .append("rollbackVerified", true).append("verificationRecordsRetained", true).toJson());
        }
    }

    private static long transactionCount(MongoTemplate mongo, String name) {
        var status = mongo.getMongoDatabaseFactory().getMongoDatabase("admin").runCommand(new Document("serverStatus", 1));
        return ((Number) status.get("transactions", Document.class).get(name)).longValue();
    }

    private static class ExpectedRollback extends RuntimeException { }
}
