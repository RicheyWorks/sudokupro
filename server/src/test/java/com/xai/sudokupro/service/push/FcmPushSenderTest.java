package com.xai.sudokupro.service.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link FcmPushSender} against a loopback HTTP server faking both
 * Google's OAuth token endpoint and the FCM v1 send endpoint (same approach as
 * ServerApiTest — the HttpClient is built internally, so a real socket is the
 * honest seam). The service-account JSON is generated with a throwaway RSA key,
 * which also lets the test VERIFY the RS256 signature on the minted JWT.
 */
class FcmPushSenderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path tempDir;

    private HttpServer server;
    private KeyPair keyPair;

    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final AtomicReference<String> tokenRequestBody = new AtomicReference<>();
    private final AtomicReference<String> sendAuthHeader = new AtomicReference<>();
    private final AtomicReference<String> sendBody = new AtomicReference<>();
    private volatile int sendStatus = 200;

    @BeforeEach
    void startFakeGoogle() throws Exception {
        keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();

        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/token", ex -> {
            tokenRequests.incrementAndGet();
            tokenRequestBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, 200, "{\"access_token\":\"fake-access-token\",\"expires_in\":3600}");
        });
        server.createContext("/v1/send", ex -> {
            sendAuthHeader.set(ex.getRequestHeaders().getFirst("Authorization"));
            sendBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, sendStatus, "{}");
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private static void respond(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private Path writeServiceAccount() throws IOException {
        String pem = "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(keyPair.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
        String json = MAPPER.createObjectNode()
            .put("type", "service_account")
            .put("project_id", "sudokupro-test")
            .put("client_email", "push@sudokupro-test.iam.gserviceaccount.com")
            .put("token_uri", baseUrl() + "/token")
            .put("private_key", pem)
            .toString();
        Path file = tempDir.resolve("service-account.json");
        Files.writeString(file, json);
        return file;
    }

    private FcmPushSender enabledSender() throws IOException {
        FcmPushSender sender = new FcmPushSender(new SimpleMeterRegistry(), true,
            writeServiceAccount().toString(), baseUrl() + "/v1/send");
        sender.init();
        return sender;
    }

    @Test
    void sendsV1PayloadWithBearerTokenAndVerifiableJwt() throws Exception {
        FcmPushSender sender = enabledSender();

        PushSender.PushResult result = sender.send("device-token-1", "SudokuPro", "You are up!", "DUEL");

        assertEquals(PushSender.PushResult.SENT, result);
        assertEquals("Bearer fake-access-token", sendAuthHeader.get());

        JsonNode message = MAPPER.readTree(sendBody.get()).path("message");
        assertEquals("device-token-1", message.path("token").asText());
        assertEquals("SudokuPro", message.path("notification").path("title").asText());
        assertEquals("You are up!", message.path("notification").path("body").asText());
        assertEquals("DUEL", message.path("data").path("type").asText());

        // The OAuth request must carry a JWT-bearer assertion whose RS256
        // signature verifies against the service account's public key.
        String assertion = null;
        for (String pair : tokenRequestBody.get().split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals("assertion")) assertion = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
        }
        assertNotNull(assertion, "token request must carry an assertion");
        String[] segments = assertion.split("\\.");
        assertEquals(3, segments.length, "assertion must be a signed JWT");

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update((segments[0] + "." + segments[1]).getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(segments[2])),
            "JWT signature must verify against the service-account key");

        JsonNode claims = MAPPER.readTree(Base64.getUrlDecoder().decode(segments[1]));
        assertEquals("push@sudokupro-test.iam.gserviceaccount.com", claims.path("iss").asText());
        assertEquals(baseUrl() + "/token", claims.path("aud").asText());
        assertEquals("https://www.googleapis.com/auth/firebase.messaging", claims.path("scope").asText());
    }

    @Test
    void accessTokenIsCachedAcrossSends() throws Exception {
        FcmPushSender sender = enabledSender();

        sender.send("t1", "SudokuPro", "one", "N");
        sender.send("t2", "SudokuPro", "two", "N");

        assertEquals(1, tokenRequests.get(), "second send must reuse the cached access token");
    }

    @Test
    void deadDeviceTokenMapsToInvalidToken() throws Exception {
        FcmPushSender sender = enabledSender();
        sendStatus = 404; // FCM v1: UNREGISTERED

        assertEquals(PushSender.PushResult.INVALID_TOKEN,
            sender.send("dead-token", "SudokuPro", "hello", "N"));
    }

    @Test
    void serverErrorMapsToFailedNotInvalid() throws Exception {
        FcmPushSender sender = enabledSender();
        sendStatus = 503;

        assertEquals(PushSender.PushResult.FAILED,
            sender.send("ok-token", "SudokuPro", "hello", "N"));
    }

    @Test
    void disabledSenderAttemptsNothing() {
        FcmPushSender sender = new FcmPushSender(new SimpleMeterRegistry(), false, "", "");
        sender.init(); // must not throw without a key when disabled

        assertFalse(sender.isEnabled());
        assertEquals(PushSender.PushResult.DISABLED, sender.send("t", "a", "b", "c"));
        assertNull(sendAuthHeader.get(), "disabled sender must not touch the network");
    }

    @Test
    void enabledWithoutReadableKeyFailsStartupFast() {
        FcmPushSender sender = new FcmPushSender(new SimpleMeterRegistry(), true,
            tempDir.resolve("missing.json").toString(), "");

        assertThrows(IllegalStateException.class, sender::init,
            "enabled-but-misconfigured FCM must fail startup, not limp along");
    }
}
