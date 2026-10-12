package dev.vaullet.auth.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A mail server that accepts every message and keeps it, for Keycloak to send to in tests.
 *
 * <p>In the test JVM rather than in a container: there is no image to pull, and what arrived is a
 * method call away from the assertion. Keycloak, in its container, reaches it through Testcontainers'
 * host port forwarding as {@code host.testcontainers.internal} — see {@link KeycloakTestContainer}.
 *
 * <p>Just enough SMTP for Keycloak's mail client with no TLS and no authentication, which is how the
 * test realm is configured. Each message is also logged with its first link, so whoever runs
 * {@code TestAuthApplication} can follow the invitation a new end user would have received.
 */
public final class SmtpSink {

    private static final Logger log = LoggerFactory.getLogger(SmtpSink.class);
    private static final Pattern LINK = Pattern.compile("https?://\\S+");
    private static final Pattern SUBJECT = Pattern.compile("(?m)^Subject: (.*)$");

    private static final List<Message> RECEIVED = new CopyOnWriteArrayList<>();
    private static @Nullable ServerSocket server;

    private SmtpSink() {}

    /** One message as it arrived: who it was for, and everything after {@code DATA}. */
    public record Message(List<String> recipients, String data) {

        public boolean isTo(String address) {
            return recipients.stream().anyMatch(recipient -> recipient.equalsIgnoreCase(address));
        }

        public String subject() {
            Matcher matcher = SUBJECT.matcher(data);
            return matcher.find() ? matcher.group(1).strip() : "";
        }

        /** The first link in the message: in Keycloak's emails, the plain-text part's action link. */
        public Optional<String> link() {
            Matcher matcher = LINK.matcher(data);
            return matcher.find() ? Optional.of(matcher.group()) : Optional.empty();
        }

        @Override
        public String toString() {
            return "Message[to=" + recipients + ", subject=" + subject() + "]";
        }
    }

    /** The port it listens on, starting it on first use. */
    public static synchronized int port() {
        ServerSocket running = server;
        if (running == null) {
            try {
                running = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot open the test SMTP sink", e);
            }
            server = running;
            ServerSocket listening = running;
            Thread.ofVirtual().name("smtp-sink").start(() -> accept(listening));
        }
        return running.getLocalPort();
    }

    /** Every message received so far for this address, oldest first. */
    public static List<Message> messagesTo(String address) {
        return RECEIVED.stream().filter(message -> message.isTo(address)).toList();
    }

    private static void accept(ServerSocket listening) {
        while (!listening.isClosed()) {
            try {
                Socket socket = listening.accept();
                Thread.ofVirtual().start(() -> converse(socket));
            } catch (IOException closed) {
                return;
            }
        }
    }

    private static void converse(Socket socket) {
        try (socket;
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                OutputStream out = socket.getOutputStream()) {
            reply(out, "220 vaullet-test-sink ESMTP");
            List<String> recipients = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null) {
                String command = line.toUpperCase(Locale.ROOT);
                if (command.startsWith("EHLO")) {
                    reply(out, "250-vaullet-test-sink");
                    reply(out, "250 8BITMIME");
                } else if (command.startsWith("MAIL FROM")) {
                    recipients.clear();
                    reply(out, "250 OK");
                } else if (command.startsWith("RCPT TO")) {
                    recipients.add(address(line));
                    reply(out, "250 OK");
                } else if (command.equals("DATA")) {
                    reply(out, "354 End data with <CR><LF>.<CR><LF>");
                    Message message = new Message(List.copyOf(recipients), body(in));
                    RECEIVED.add(message);
                    log.info("Mail for {}: {} {}", message.recipients(), message.subject(), message.link().orElse(""));
                    reply(out, "250 OK");
                } else if (command.equals("QUIT")) {
                    reply(out, "221 Bye");
                    return;
                } else {
                    // HELO, RSET, NOOP: nothing to do but agree.
                    reply(out, "250 OK");
                }
            }
        } catch (IOException dropped) {
            log.debug("SMTP connection dropped", dropped);
        }
    }

    /** Lines up to the lone dot, with SMTP's dot-stuffing undone. */
    private static String body(BufferedReader in) throws IOException {
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null && !line.equals(".")) {
            data.append(line.startsWith("..") ? line.substring(1) : line).append('\n');
        }
        return data.toString();
    }

    /** {@code RCPT TO:<jane@example.com>} to {@code jane@example.com}. */
    private static String address(String line) {
        int open = line.indexOf('<');
        int close = line.indexOf('>');
        return open >= 0 && close > open ? line.substring(open + 1, close) : line.substring(line.indexOf(':') + 1).strip();
    }

    private static void reply(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }
}
