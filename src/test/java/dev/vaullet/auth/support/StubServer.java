package dev.vaullet.auth.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * An HTTP server in the test JVM that answers whatever the test tells it to, and remembers what it
 * was sent — a stand-in for a service a test must not depend on, such as the ledger, which is not
 * deployed, or a Keycloak token endpoint whose answers a test needs to choose.
 *
 * <p>The JDK's own {@code HttpServer}, on a free loopback port: nothing to pull, nothing to start
 * outside the JVM. Requests are handled on virtual threads, one each, so two clients calling at once
 * really are served at once — a stand-in that queued them would hide exactly the concurrency a relay
 * test is about.
 */
public final class StubServer implements AutoCloseable {

    /** One request as it arrived. Header names are lower-cased. */
    public record Request(String method, String path, Map<String, String> headers, String body) {

        public @Nullable String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    /** What to answer: a status, a content type and a body, after an optional pause. */
    public record Response(int status, String contentType, String body, Duration delay) {

        public static Response json(int status, String body) {
            return new Response(status, "application/json", body, Duration.ZERO);
        }

        /** An RFC 9457 problem document, as every Vaullet service sends one. */
        public static Response problem(int status, String code) {
            return new Response(status, "application/problem+json", """
                    {"type": "https://docs.vaullet.dev/errors/%s", "status": %d, "code": "%s", "detail": "stub"}"""
                    .formatted(code.toLowerCase(Locale.ROOT).replace('_', '-'), status, code), Duration.ZERO);
        }

        public Response after(Duration pause) {
            return new Response(status, contentType, body, pause);
        }
    }

    private final HttpServer server;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Request> received = new CopyOnWriteArrayList<>();
    private volatile Function<Request, Response> responder;

    public StubServer(Function<Request, Response> responder) {
        this.responder = responder;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open a stub HTTP server", e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(threads);
        server.start();
    }

    /** Where it listens, without a trailing slash. */
    public String url() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    /** Answer from now on as this says. */
    public void answer(Function<Request, Response> next) {
        responder = next;
    }

    /** Every request so far, oldest first. */
    public List<Request> requests() {
        return List.copyOf(received);
    }

    public void forget() {
        received.clear();
    }

    @Override
    public void close() {
        server.stop(0);
        threads.close();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Map<String, String> headers = exchange.getRequestHeaders().entrySet().stream()
                    .collect(Collectors.toMap(
                            header -> header.getKey().toLowerCase(Locale.ROOT),
                            header -> String.join(",", header.getValue()),
                            (first, second) -> first + "," + second));
            Request request = new Request(
                    exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, body);
            received.add(request);

            Response response = responder.apply(request);
            if (!response.delay().isZero()) {
                Thread.sleep(response.delay());
            }
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
            exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
