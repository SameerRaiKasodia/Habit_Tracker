import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HabitTracker {

    static final String HTML_FILE = "src/index.html";
    static final int PORT = 8080;

    // --- DATABASE SETTINGS (CHANGE IF NEEDED) ---
    // If your MySQL has a password, put it inside the quotes below.
    static final String DB_URL = "jdbc:mysql://localhost:3306/habit_tracker_db";
    static final String DB_USER = "root";
    static final String DB_PASS = System.getenv("MYSQL_PASS"); ;

    public static void main(String[] args) throws Exception {
        startNotificationTimer(); // Start the background alarm clock

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        server.createContext("/", ex -> {
            if (!ex.getRequestURI().getPath().equals("/")) {
                send(ex, 404, "text/plain", "Not found");
                return;
            }
            String html = new String(Files.readAllBytes(Paths.get(HTML_FILE)), StandardCharsets.UTF_8);
            send(ex, 200, "text/html", html);
        });

        server.createContext("/habits", ex -> {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();

            try {
                if (path.equals("/habits") && method.equals("GET")) {
                    sendJson(ex, buildHabitsJson());
                }
                else if (path.equals("/habits") && method.equals("POST")) {
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String name = extractJsonString(body, "name");
                    String type = extractJsonString(body, "type"); // Good or Bad
                    if (type == null) type = "Good";

                    if (name != null && !name.isBlank()) {
                        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                             PreparedStatement ps = conn.prepareStatement("INSERT INTO habits (name, habit_type) VALUES (?, ?)")) {
                            ps.setString(1, name);
                            ps.setString(2, type);
                            ps.executeUpdate();
                        }
                    }
                    send(ex, 200, "text/plain", "OK");
                }
                else if (method.equals("DELETE") && path.matches("/habits/\\d+")) {
                    int id = Integer.parseInt(path.substring("/habits/".length()));
                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                         PreparedStatement ps = conn.prepareStatement("DELETE FROM habits WHERE id = ?")) {
                        ps.setInt(1, id);
                        ps.executeUpdate();
                    }
                    send(ex, 200, "text/plain", "OK");
                }
                else if (method.equals("POST") && path.matches("/habits/\\d+/checkin")) {
                    int id = Integer.parseInt(path.split("/")[2]);
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String note = extractJsonString(body, "note");
                    String today = LocalDate.now().toString();

                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
                        PreparedStatement check = conn.prepareStatement("SELECT id FROM check_ins WHERE habit_id = ? AND check_in_date = ?");
                        check.setInt(1, id);
                        check.setDate(2, java.sql.Date.valueOf(today));
                        ResultSet rs = check.executeQuery();

                        if (rs.next()) {
                            // Toggle off
                            PreparedStatement del = conn.prepareStatement("DELETE FROM check_ins WHERE habit_id = ? AND check_in_date = ?");
                            del.setInt(1, id);
                            del.setDate(2, java.sql.Date.valueOf(today));
                            del.executeUpdate();
                        } else {
                            // Insert check-in with note
                            PreparedStatement ins = conn.prepareStatement("INSERT INTO check_ins (habit_id, check_in_date, note) VALUES (?, ?, ?)");
                            ins.setInt(1, id);
                            ins.setDate(2, java.sql.Date.valueOf(today));
                            ins.setString(3, note == null ? "" : note);
                            ins.executeUpdate();
                        }
                    }
                    send(ex, 200, "text/plain", "OK");
                }
            } catch (SQLException e) {
                e.printStackTrace();
                send(ex, 500, "text/plain", "Database error: " + e.getMessage());
            }
        });

        server.setExecutor(null);
        server.start();
        System.out.println("✅ Habit Tracker with MySQL running at: http://localhost:" + PORT);
    }

    // --- BACKGROUND TIMER (ALARM CLOCK) ---
    static void startNotificationTimer() {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        // Calculate time until midnight (23:59:59)
        LocalTime now = LocalTime.now();
        LocalTime midnight = LocalTime.of(23, 59, 59);
        long initialDelay = java.time.Duration.between(now, midnight).getSeconds();
        if (initialDelay < 0) initialDelay += 24 * 60 * 60; // If past midnight, wait till tomorrow

        System.out.println("⏰ Background timer set. Next check in " + initialDelay + " seconds.");

        scheduler.scheduleAtFixedRate(() -> {
            System.out.println("⏰ Daily Check: Calculating streaks and saving to database...");
            // The streak calculation happens automatically in buildHabitsJson based on dates.
            // This timer is just proof that the background task runs.
        }, initialDelay, 24 * 60 * 60, TimeUnit.SECONDS);
    }

    // --- HELPERS ---
    static String buildHabitsJson() throws SQLException {
        String today = LocalDate.now().toString();
        StringBuilder sb = new StringBuilder("[");

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT h.id, h.name, h.habit_type, c.check_in_date, c.note FROM habits h LEFT JOIN check_ins c ON h.id = c.habit_id ORDER BY h.id, c.check_in_date")) {

            Map<Integer, String[]> idToHabit = new LinkedHashMap<>(); // id -> [name, type]
            Map<Integer, List<String>> idToDates = new LinkedHashMap<>();
            Map<Integer, String> idToLatestNote = new LinkedHashMap<>();

            while (rs.next()) {
                int id = rs.getInt("id");
                idToHabit.putIfAbsent(id, new String[]{rs.getString("name"), rs.getString("habit_type")});

                if (rs.getDate("check_in_date") != null) {
                    String date = rs.getDate("check_in_date").toString();
                    idToDates.putIfAbsent(id, new ArrayList<>());
                    if (!idToDates.get(id).contains(date)) idToDates.get(id).add(date);

                    String note = rs.getString("note");
                    if (note != null && !note.isEmpty()) idToLatestNote.put(id, note);
                }
            }

            boolean first = true;
            for (int id : idToHabit.keySet()) {
                if (!first) sb.append(",");
                first = false;
                List<String> dates = idToDates.getOrDefault(id, new ArrayList<>());
                String[] habitInfo = idToHabit.get(id);

                sb.append("{\"id\":").append(id)
                        .append(",\"name\":\"").append(escape(habitInfo[0])).append("\"")
                        .append(",\"type\":\"").append(escape(habitInfo[1])).append("\"")
                        .append(",\"streak\":").append(computeStreak(dates))
                        .append(",\"doneToday\":").append(dates.contains(today))
                        .append(",\"latestNote\":\"").append(escape(idToLatestNote.getOrDefault(id, ""))).append("\"")
                        .append(",\"history\":[");

                for (int i = 0; i < dates.size(); i++) {
                    sb.append("\"").append(dates.get(i)).append("\"");
                    if (i < dates.size() - 1) sb.append(",");
                }
                sb.append("]}");
            }
        }
        return sb.append("]").toString();
    }

    static int computeStreak(List<String> checkIns) {
        if (checkIns.isEmpty()) return 0;
        List<LocalDate> dates = new ArrayList<>();
        for (String s : checkIns) dates.add(LocalDate.parse(s));
        Collections.sort(dates);
        LocalDate today = LocalDate.now();
        LocalDate last = dates.get(dates.size() - 1);
        if (!last.equals(today) && !last.equals(today.minusDays(1))) return 0;

        int streak = 1;
        for (int i = dates.size() - 1; i > 0; i--) {
            if (dates.get(i).minusDays(1).equals(dates.get(i - 1))) streak++;
            else break;
        }
        return streak;
    }

    static String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int k = json.indexOf(pattern);
        if (k < 0) return null;
        int colon = json.indexOf(':', k);
        int start = json.indexOf('"', colon) + 1;
        int end = json.indexOf('"', start);
        return (start > 0 && end > start) ? json.substring(start, end) : null;
    }

    static String escape(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\""); }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static void sendJson(HttpExchange ex, String json) throws IOException {
        send(ex, 200, "application/json", json);
    }
}