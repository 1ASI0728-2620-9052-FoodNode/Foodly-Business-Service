package com.foodly.business.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodly.business.BusinessServiceApplication;
import com.foodly.business.application.*;
import com.foodly.business.domain.model.*;
import com.foodly.business.infrastructure.persistence.OutboxDocument;
import com.mongodb.client.MongoClients;
import com.rabbitmq.client.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/** Real integration probe: full Business application, real MongoDB and RabbitMQ; no mocks or record cleanup. */
public class RabbitOutboxVerification {
    private static final String EXCHANGE = "foodly.business";
    private static final List<String> TYPES = List.of("HuariqueRegistrado", "PerfilDelHuariqueActualizado",
            "EstadoAbiertoCerradoCambiado", "MenuCreado", "MenuActualizado");
    private static final List<String> checks = new ArrayList<>();
    private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected the project root");
        var root = Path.of(args[0]).toRealPath();
        var runId = UUID.randomUUID().toString().replace("-", "");
        var database = "foodly_rabbitmq_it_" + runId;
        var mongoUri = "mongodb://127.0.0.1:27018/" + database + "?replicaSet=rs0";
        var profileQueue = "foodly.business.outbox.verify." + runId + ".huarique";
        var menuQueue = "foodly.business.outbox.verify." + runId + ".menu";
        var reportFile = root.resolve(".local/rabbitmq/verification-" + runId + ".json");
        var report = new LinkedHashMap<String, Object>();
        report.put("runId", runId); report.put("mongoUri", mongoUri);
        report.put("amqpEndpoint", "127.0.0.1:5673"); report.put("virtualHost", "/foodly-business-dev");
        report.put("httpPort", 8082); report.put("checks", checks);
        boolean brokerStopped = false;
        boolean completed = false;
        try {
            if (!"amqp://127.0.0.1:5673".equals(System.getenv("RABBITMQ_ADDRESSES"))
                    || !"/foodly-business-dev".equals(System.getenv("RABBITMQ_VHOST"))
                    || !"true".equals(System.getenv("OUTBOX_ENABLED")))
                throw new IllegalStateException("Only the isolated local broker with Outbox enabled is permitted");
            try (var mongo = MongoClients.create("mongodb://127.0.0.1:27018/?replicaSet=rs0&serverSelectionTimeoutMS=3000")) {
                for (var name : mongo.listDatabaseNames()) if (database.equals(name))
                    throw new IllegalStateException("Refusing to reuse an existing MongoDB database");
            }
            declareTopology(profileQueue, menuQueue);
            try (var context = new SpringApplicationBuilder(BusinessServiceApplication.class).run(
                    "--server.port=8082", "--spring.data.mongodb.uri=" + mongoUri,
                    "--business.events.exchange=" + EXCHANGE, "--business.outbox.enabled=true")) {
                var mongo = context.getBean(MongoTemplate.class);
                var huariques = context.getBean(HuariqueApplicationService.class);
                var menus = context.getBean(MenuApplicationService.class);
                var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                var json = context.getBean(ObjectMapper.class);
                check(database.equals(mongo.getDb().getName()), "full_application_uses_new_isolated_database");
                awaitHealth(200);
                check(true, "health_up_with_real_broker");

                var profile = new ProfileData("RabbitMQ integration verification", "Isolated integration run " + runId,
                        CuisineType.OTHER, new PriceRange(BigDecimal.ZERO, BigDecimal.TEN),
                        new GeoCoordinates(-12.0464, -77.0428), List.of(), List.of());
                var registered = tx.execute(status -> {
                    var h = huariques.register("rabbit-it-" + runId, profile);
                    var event = mongo.findOne(Query.query(where("huariqueId").is(h.id()).and("type").is("HuariqueRegistrado")), OutboxDocument.class);
                    check(event != null && event.publishedAt() == null && event.attempts() == 0,
                            "new_profile_event_pending_inside_real_transaction");
                    return event;
                });
                await(() -> mongo.findById(registered.id(), OutboxDocument.class).publishedAt() != null, "profile publication confirm");
                var published = mongo.findById(registered.id(), OutboxDocument.class);
                check(published.attempts() >= 1 && published.publishedAt() != null, "profile_outbox_marked_after_real_publisher_confirm");
                report.put("normalPublication", inspectMessage(profileQueue, published, json));

                controlBroker(root, "Stop", runId);
                brokerStopped = true;
                awaitHealth(503);
                check(true, "health_down_during_real_broker_outage");
                var menuEvent = tx.execute(status -> {
                    menus.replace(registered.huariqueId(), "rabbit-it-" + runId, List.of(), null);
                    var event = mongo.findOne(Query.query(where("huariqueId").is(registered.huariqueId()).and("type").is("MenuCreado")), OutboxDocument.class);
                    check(event != null && event.publishedAt() == null && event.attempts() == 0,
                            "menu_event_committed_while_broker_down");
                    return event;
                });
                await(() -> {
                    var e = mongo.findById(menuEvent.id(), OutboxDocument.class);
                    return e.attempts() > 0 && e.lastError() != null;
                }, "failed real broker attempt");
                var pending = mongo.findById(menuEvent.id(), OutboxDocument.class);
                check(pending.publishedAt() == null && pending.payload().equals(menuEvent.payload()), "event_retained_pending_after_connection_failure");
                report.put("duringOutage", Map.of("eventId", pending.id().toString(), "attempts", pending.attempts(),
                        "published", false, "lastError", pending.lastError()));

                controlBroker(root, "Start", runId);
                brokerStopped = false;
                awaitHealth(200);
                check(true, "health_up_after_broker_recovery");
                await(() -> mongo.findById(menuEvent.id(), OutboxDocument.class).publishedAt() != null, "automatic retry after recovery");
                var recovered = mongo.findById(menuEvent.id(), OutboxDocument.class);
                check(recovered.id().equals(pending.id()) && recovered.payload().equals(pending.payload())
                                && recovered.attempts() > pending.attempts() && recovered.lastError() == null,
                        "same_event_and_payload_delivered_by_automatic_retry");
                report.put("recoveredPublication", inspectMessage(menuQueue, recovered, json));
                // The durable queue's first message must also survive the node restart.
                report.put("normalMessageAfterRestart", inspectMessage(profileQueue, published, json));
                check(mongo.findById(registered.id(), OutboxDocument.class) != null
                        && mongo.findById(menuEvent.id(), OutboxDocument.class) != null, "both_new_outbox_records_retained");
                report.put("healthHttpStatuses", List.of(200, 503, 200));
                completed = true;
            }
        } finally {
            if (brokerStopped) {
                try { controlBroker(root, "Start", runId); }
                catch (Exception recoveryFailure) { report.put("brokerRecoveryRequired", true); }
            }
            report.put("completed", completed);
            report.put("verifiedAt", Instant.now().toString());
            report.put("recordsAndMessagesRetained", true);
            Files.writeString(reportFile, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
            System.out.println("RABBITMQ_OUTBOX_REPORT=" + reportFile);
            System.out.println("RABBITMQ_OUTBOX_COMPLETED=" + completed + ", checks=" + checks.size());
        }
    }

    private static Connection connection() throws Exception {
        var factory = new ConnectionFactory();
        factory.setHost("127.0.0.1"); factory.setPort(5673);
        factory.setVirtualHost("/foodly-business-dev");
        factory.setUsername(Objects.requireNonNull(System.getenv("RABBITMQ_USERNAME")));
        factory.setPassword(Objects.requireNonNull(System.getenv("RABBITMQ_PASSWORD")));
        factory.setConnectionTimeout(3000); factory.setAutomaticRecoveryEnabled(false);
        return factory.newConnection("foodly-isolated-outbox-verification");
    }

    private static void declareTopology(String profileQueue, String menuQueue) throws Exception {
        try (var connection = connection(); var channel = connection.createChannel()) {
            channel.exchangeDeclare(EXCHANGE, "topic", true);
            channel.queueDeclare("foodly.business.dev.audit", true, false, false, Map.of("x-queue-type", "classic"));
            for (var type : TYPES) channel.queueBind("foodly.business.dev.audit", EXCHANGE, "business." + type);
            channel.queueDeclare(profileQueue, true, false, false, Map.of("x-queue-type", "classic"));
            channel.queueDeclare(menuQueue, true, false, false, Map.of("x-queue-type", "classic"));
            channel.queueBind(profileQueue, EXCHANGE, "business.HuariqueRegistrado");
            channel.queueBind(menuQueue, EXCHANGE, "business.MenuCreado");
        }
        check(true, "durable_topic_exchange_queues_and_contract_bindings_declared");
    }

    private static Map<String, Object> inspectMessage(String queue, OutboxDocument event, ObjectMapper json) throws Exception {
        try (var connection = connection(); var channel = connection.createChannel()) {
            var message = channel.basicGet(queue, false);
            check(message != null, "real_routed_message_present_in_" + queue);
            try {
                var text = new String(message.getBody(), StandardCharsets.UTF_8);
                check(event.id().toString().equals(message.getProps().getMessageId())
                                && event.type().equals(message.getProps().getType())
                                && Integer.valueOf(2).equals(message.getProps().getDeliveryMode())
                                && ("business." + event.type()).equals(message.getEnvelope().getRoutingKey())
                                && event.payload().equals(text)
                                && event.id().toString().equals(json.readTree(text).path("eventId").asText()),
                        "real_message_id_routing_payload_and_persistence_match_outbox");
                return Map.of("queue", queue, "eventId", event.id().toString(), "type", event.type(),
                        "attempts", event.attempts(), "publishedAt", event.publishedAt().toString(),
                        "persistentMessage", true, "payloadSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(message.getBody())));
            } finally {
                // Keep the message; this probe never purges or deletes existing messages.
                channel.basicNack(message.getEnvelope().getDeliveryTag(), false, true);
            }
        }
    }

    private static void controlBroker(Path root, String action, String runId) throws Exception {
        var log = root.resolve(".local/rabbitmq/" + action.toLowerCase(Locale.ROOT) + "-" + runId + ".log");
        var child = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                root.resolve("scripts/rabbitmq.ps1").toString(), "-Action", action)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!child.waitFor(55, java.util.concurrent.TimeUnit.SECONDS) || child.exitValue() != 0)
            throw new IllegalStateException("Dedicated broker " + action + " failed; inspect the private control log");
    }

    private static void awaitHealth(int expected) throws Exception {
        await(() -> {
            try {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8082/actuator/health"))
                        .timeout(Duration.ofSeconds(8)).GET().build();
                return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == expected;
            } catch (Exception e) { return false; }
        }, "HTTP health " + expected);
    }

    private static void await(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) { if (condition.getAsBoolean()) return; Thread.sleep(300); }
        throw new IllegalStateException("Timed out waiting for " + description);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new IllegalStateException("Integration verification failed: " + description);
        checks.add(description);
    }
}
