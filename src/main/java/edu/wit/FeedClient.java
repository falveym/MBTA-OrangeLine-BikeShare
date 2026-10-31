package edu.wit;
import java.io.*;
import java.net.*;

final class FeedClient {
    private FeedClient() {}
    static byte[] fetch(String url) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) Thread.sleep(1000L << (attempt - 1));
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            try {
                if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                try (InputStream in = c.getInputStream()) { return in.readAllBytes(); }
            } catch (IOException e) {
                last = e;
                System.err.println("Fetch attempt " + (attempt + 1) + " failed for " + url + ": " + e.getMessage());
            } finally { c.disconnect(); }
        }
        throw last;
    }
}
