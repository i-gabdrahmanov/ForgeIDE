package dev.forgeide.runtime.outward;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.forgeide.core.port.OutwardActionException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Shared REST plumbing for the PR/Jira clients (T17): auth header, JSON parsing, and turning a
 * non-2xx or transport failure into the one {@link OutwardActionException} the engine already
 * knows how to classify as {@code FAILED(script)} and retry by policy — callers don't each
 * re-invent that mapping.
 */
final class HttpApi {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private HttpApi() {
    }

    /** @return the parsed JSON body, or an empty object node for a body-less response (e.g. a
     * {@code 204} from a Jira transition) — callers path/has-check rather than assume shape. */
    static JsonNode send(HttpClient http, ObjectMapper mapper, HttpRequest.Builder builder, String bearerToken,
                          String apiLabel) throws OutwardActionException {
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        builder.header("Accept", "application/json").timeout(TIMEOUT);

        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new OutwardActionException(apiLabel + " call failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OutwardActionException(apiLabel + " call interrupted", e);
        }
        if (response.statusCode() >= 400) {
            throw new OutwardActionException(apiLabel + " returned " + response.statusCode() + ": " + response.body());
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new OutwardActionException(apiLabel + " returned unparsable JSON: " + body, e);
        }
    }
}
