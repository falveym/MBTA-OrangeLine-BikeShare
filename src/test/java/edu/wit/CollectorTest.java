package edu.wit;

public class CollectorTest {
    @org.junit.Test
    public void offlineChecks() throws Exception {
        CollectorChecks.main(new String[0]);
    }

    @org.junit.Test
    public void retriesHttpFailuresAndReportsExhaustion() throws Exception {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/retry", exchange -> {
            int status = attempts.incrementAndGet() < 3 ? 503 : 200;
            byte[] body = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.createContext("/fail", exchange -> {
            attempts.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            org.junit.Assert.assertArrayEquals("ok".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    FeedClient.fetch(base + "/retry"));
            org.junit.Assert.assertEquals(3, attempts.get());
            attempts.set(0);
            try {
                FeedClient.fetch(base + "/fail");
                org.junit.Assert.fail("Failed fetch accepted");
            } catch (java.io.IOException expected) {
                org.junit.Assert.assertTrue(expected.getMessage().contains("503"));
            }
            org.junit.Assert.assertEquals(3, attempts.get());
        } finally { server.stop(0); }
    }
}
