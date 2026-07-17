package com.xai.sudokupro.service.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * FCM HTTP v1 sender (replaces the legacy server-key integration removed under
 * AUDIT P1-5 — Google shut that API down in 2024). Authenticates with an OAuth2
 * service account: a self-signed RS256 JWT is exchanged at the account's
 * {@code token_uri} for a short-lived access token, which is cached until close
 * to expiry. Implemented directly on {@code java.net.http} + JDK crypto — no
 * Google SDK dependency.
 *
 * <p>Disabled by default. When {@code sudokupro.fcm.enabled=true}, startup fails
 * fast (SecretsGuard philosophy) if the service-account file is missing or
 * malformed rather than limping along with pushes silently broken.
 */
@Service
public class FcmPushSender implements PushSender {

    private static final Logger logger = LoggerFactory.getLogger(FcmPushSender.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    /** Refresh the cached access token this long before it actually expires. */
    private static final Duration TOKEN_SAFETY_WINDOW = Duration.ofMinutes(5);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(HTTP_TIMEOUT)
        .build();
    private final MeterRegistry meterRegistry;

    private final boolean enabled;
    private final String serviceAccountFile;
    private final String sendEndpointOverride;

    // Loaded from the service-account JSON at startup (when enabled)
    private String clientEmail;
    private String tokenUri;
    private String sendEndpoint;
    private PrivateKey privateKey;

    // Access-token cache
    private final Object tokenLock = new Object();
    private String cachedToken;
    private Instant cachedTokenExpiry = Instant.EPOCH;

    public FcmPushSender(MeterRegistry meterRegistry,
                         @Value("${sudokupro.fcm.enabled:false}") boolean enabled,
                         @Value("${sudokupro.fcm.service-account-file:}") String serviceAccountFile,
                         @Value("${sudokupro.fcm.send-endpoint:}") String sendEndpointOverride) {
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
        this.serviceAccountFile = serviceAccountFile;
        this.sendEndpointOverride = sendEndpointOverride;
    }

    @PostConstruct
    void init() {
        if (!enabled) {
            logger.info("FCM push disabled (sudokupro.fcm.enabled=false) — notifications stay WebSocket-only");
            return;
        }
        try {
            loadServiceAccount(Path.of(serviceAccountFile));
            logger.info("FCM push enabled for service account {} (endpoint {})", clientEmail, sendEndpoint);
        } catch (Exception e) {
            throw new IllegalStateException(
                "sudokupro.fcm.enabled=true but the service account cannot be loaded from '"
                + serviceAccountFile + "': " + e.getMessage(), e);
        }
    }

    private void loadServiceAccount(Path path) throws Exception {
        JsonNode json = mapper.readTree(Files.readString(path));
        this.clientEmail = requireField(json, "client_email");
        this.tokenUri = requireField(json, "token_uri");
        String projectId = requireField(json, "project_id");
        this.privateKey = parsePkcs8Pem(requireField(json, "private_key"));
        this.sendEndpoint = sendEndpointOverride != null && !sendEndpointOverride.isBlank()
            ? sendEndpointOverride
            : "https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send";
    }

    private static String requireField(JsonNode json, String field) {
        JsonNode node = json.get(field);
        if (node == null || node.asText().isBlank()) {
            throw new IllegalArgumentException("service account JSON is missing '" + field + "'");
        }
        return node.asText();
    }

    private static PrivateKey parsePkcs8Pem(String pem) throws Exception {
        String base64 = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public PushResult send(String deviceToken, String title, String body, String type) {
        if (!enabled) return PushResult.DISABLED;
        try {
            String accessToken = accessToken();

            ObjectNode message = mapper.createObjectNode();
            ObjectNode msg = message.putObject("message");
            msg.put("token", deviceToken);
            ObjectNode notification = msg.putObject("notification");
            notification.put("title", title);
            notification.put("body", body);
            // FCM v1 requires data values to be strings.
            msg.putObject("data").put("type", type);

            HttpRequest request = HttpRequest.newBuilder(URI.create(sendEndpoint))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(message.toString(), StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                meterRegistry.counter("sudokupro.push.sent").increment();
                return PushResult.SENT;
            }
            // 404 = UNREGISTERED, 410 = expired: the token is dead, not the request.
            if (status == 404 || status == 410) {
                meterRegistry.counter("sudokupro.push.invalid_token").increment();
                logger.info("FCM reports dead device token (HTTP {}) — dropping it", status);
                return PushResult.INVALID_TOKEN;
            }
            meterRegistry.counter("sudokupro.push.failed").increment();
            logger.warn("FCM send failed: HTTP {} — {}", status, truncate(response.body()));
            return PushResult.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            meterRegistry.counter("sudokupro.push.failed").increment();
            return PushResult.FAILED;
        } catch (Exception e) {
            meterRegistry.counter("sudokupro.push.failed").increment();
            logger.warn("FCM send failed: {}", e.getMessage());
            return PushResult.FAILED;
        }
    }

    // ---- OAuth2 service-account token exchange -----------------------------

    private String accessToken() throws Exception {
        synchronized (tokenLock) {
            if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiry.minus(TOKEN_SAFETY_WINDOW))) {
                return cachedToken;
            }
            long now = Instant.now().getEpochSecond();
            String assertion = signJwt(now);
            String form = "grant_type=" + URLEncoder.encode(
                    "urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUri))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("OAuth token exchange failed: HTTP "
                    + response.statusCode() + " — " + truncate(response.body()));
            }
            JsonNode json = mapper.readTree(response.body());
            cachedToken = json.path("access_token").asText(null);
            if (cachedToken == null) {
                throw new IllegalStateException("OAuth token response contained no access_token");
            }
            long expiresIn = json.path("expires_in").asLong(3600);
            cachedTokenExpiry = Instant.now().plusSeconds(expiresIn);
            return cachedToken;
        }
    }

    /** Self-signed RS256 JWT per Google's service-account flow. */
    private String signJwt(long nowEpochSeconds) throws Exception {
        String header = b64url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        ObjectNode claims = mapper.createObjectNode();
        claims.put("iss", clientEmail);
        claims.put("scope", SCOPE);
        claims.put("aud", tokenUri);
        claims.put("iat", nowEpochSeconds);
        claims.put("exp", nowEpochSeconds + 3600);
        String signingInput = header + "." + b64url(claims.toString().getBytes(StandardCharsets.UTF_8));

        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + b64url(signature.sign());
    }

    private static String b64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
