package edu.wit;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.Arrays;
import java.util.stream.Collectors;

final class DailyCsv implements AutoCloseable {
    private final Path directory;
    private final String prefix, header;
    private BufferedWriter writer;
    private LocalDate date;
    DailyCsv(String prefix, String header) { this(Path.of("data", "raw"), prefix, header); }
    DailyCsv(Path directory, String prefix, String header) {
        this.directory = directory; this.prefix = prefix; this.header = header;
    }
    synchronized void write(Instant now, Object... fields) throws IOException {
        LocalDate next = now.atZone(ServiceWindow.ZONE).toLocalDate();
        try {
            if (writer == null || !next.equals(date)) {
                close();
                Files.createDirectories(directory);
                Path path = directory.resolve(prefix + "_" + next + ".csv");
                boolean empty = !Files.exists(path) || Files.size(path) == 0;
                writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                date = next;
                if (empty) writer.write(header + "\n");
            }
            writer.write(Arrays.stream(fields).map(DailyCsv::escape).collect(Collectors.joining(",")) + "\n");
            writer.flush();
        } catch (IOException e) { close(); throw e; }
    }
    private static String escape(Object value) {
        String s = String.valueOf(value);
        return s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")
                ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
    @Override public synchronized void close() {
        if (writer != null) {
            try { writer.close(); } catch (IOException e) { System.err.println("CSV close failed: " + e.getMessage()); }
            writer = null;
        }
    }
}
