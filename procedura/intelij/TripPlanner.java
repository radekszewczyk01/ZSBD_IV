package org.example;

import org.neo4j.graphdb.*;
import org.neo4j.logging.Log;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.util.*;
import java.util.stream.Stream;

public class TripPlanner {

    @Context
    public Transaction tx;

    @Context
    public Log log;

    private static final RelationshipType DRIVE = RelationshipType.withName("DRIVE");
    private static final RelationshipType VALID_ON = RelationshipType.withName("VALID_ON");
    private static final Label STOP_LABEL = Label.label("Stop");
    private static final Label TRIP_LABEL = Label.label("Trip");

    // Stała: Liczba sekund w dobie
    private static final int DAY_SECONDS = 24 * 3600;

    public static class RouteSegment {
        public String startStop;
        public String endStop;
        public String tripId;
        public String depTime;
        public String arrTime;
        public String line;
        // Dodatkowe pole informacyjne, czy to następny dzień
        public boolean isNextDay;

        public RouteSegment(String startStop, String endStop, String tripId, String depTime, String arrTime, String line, boolean isNextDay) {
            this.startStop = startStop;
            this.endStop = endStop;
            this.tripId = tripId;
            this.depTime = depTime;
            this.arrTime = arrTime;
            this.line = line;
            this.isNextDay = isNextDay;
        }
    }

    private static class State implements Comparable<State> {
        Node node;
        int timeInSeconds;
        State parent;
        Relationship viaRel;
        boolean viaNextDay; // Czy dotarcie tutaj nastąpiło "jutrzejszym" kursem?

        public State(Node node, int timeInSeconds, State parent, Relationship viaRel, boolean viaNextDay) {
            this.node = node;
            this.timeInSeconds = timeInSeconds;
            this.parent = parent;
            this.viaRel = viaRel;
            this.viaNextDay = viaNextDay;
        }

        @Override
        public int compareTo(State other) {
            return Integer.compare(this.timeInSeconds, other.timeInSeconds);
        }
    }

    @Procedure(name = "custom.findFastestRoute", mode = Mode.READ)
    public Stream<RouteSegment> findFastestRoute(
            @Name("startStopCode") String startStopCode,
            @Name("endStopCode") String endStopCode,
            @Name("startTime") String startTimeStr,
            @Name("dayOfWeek") String dayOfWeek
    ) {
        Node startNode = findNodeSmart(startStopCode);
        Node endNode = findNodeSmart(endStopCode);

        if (startNode == null || endNode == null) {
            log.error("TripPlanner: Nie znaleziono przystanków.");
            return Stream.empty();
        }

        int startTimeSeconds = parseTimeToSeconds(startTimeStr);
        String todayProp = dayOfWeek.toLowerCase();
        String tomorrowProp = getNextDay(todayProp); // Obliczamy jaki jest następny dzień

        // 2. PRE-FETCHING (Ładujemy DWA zestawy tripów: na dziś i na jutro)
        Set<String> tripsToday = new HashSet<>();
        Set<String> tripsTomorrow = new HashSet<>();

        try (ResourceIterator<Node> trips = tx.findNodes(TRIP_LABEL)) {
            while (trips.hasNext()) {
                Node trip = trips.next();
                Relationship validRel = trip.getSingleRelationship(VALID_ON, Direction.OUTGOING);
                if (validRel != null) {
                    Node calendar = validRel.getEndNode();
                    String tId = (String) trip.getProperty("trip_id");

                    // Sprawdź Dziś
                    Object isToday = calendar.getProperty(todayProp, false);
                    if (isToday instanceof Boolean && (Boolean) isToday) {
                        tripsToday.add(tId);
                    }

                    // Sprawdź Jutro
                    Object isTomorrow = calendar.getProperty(tomorrowProp, false);
                    if (isTomorrow instanceof Boolean && (Boolean) isTomorrow) {
                        tripsTomorrow.add(tId);
                    }
                }
            }
        }
        log.info("TripPlanner: Trips Today (" + todayProp + "): " + tripsToday.size() + ", Tomorrow (" + tomorrowProp + "): " + tripsTomorrow.size());

        // 3. DIJKSTRA
        PriorityQueue<State> queue = new PriorityQueue<>();
        Map<Long, Integer> bestArrivalTimes = new HashMap<>();

        // Start: false oznacza, że startujemy "dzisiaj"
        queue.add(new State(startNode, startTimeSeconds, null, null, false));
        bestArrivalTimes.put(startNode.getId(), startTimeSeconds);

        State finalState = null;

        while (!queue.isEmpty()) {
            State current = queue.poll();

            if (current.timeInSeconds > bestArrivalTimes.getOrDefault(current.node.getId(), Integer.MAX_VALUE)) {
                continue;
            }

            if (current.node.equals(endNode)) {
                finalState = current;
                break;
            }

            for (Relationship drive : current.node.getRelationships(Direction.OUTGOING, DRIVE)) {
                String tripId = (String) drive.getProperty("trip_id");

                Object depObj = drive.getProperty("dep_time");
                Object arrObj = drive.getProperty("arr_time");
                if (depObj == null || arrObj == null) continue;

                int depRaw = parseTimeToSeconds(depObj.toString());
                int arrRaw = parseTimeToSeconds(arrObj.toString());

                // --- SCENARIUSZ A: Kurs DZISIEJSZY ---
                if (tripsToday.contains(tripId)) {
                    // Normalny czas (bez przesunięcia)
                    if (depRaw >= current.timeInSeconds) {
                        relaxNeighbor(current, drive, depRaw, arrRaw, false, bestArrivalTimes, queue);
                    }
                }

                // --- SCENARIUSZ B: Kurs JUTRZEJSZY ---
                // Dodajemy 24h (DAY_SECONDS) do czasu odjazdu i przyjazdu
                if (tripsTomorrow.contains(tripId)) {
                    int depTomorrow = depRaw + DAY_SECONDS;
                    int arrTomorrow = arrRaw + DAY_SECONDS;

                    // Sprawdzamy, czy "jutrzejszy" odjazd jest po naszym obecnym czasie
                    if (depTomorrow >= current.timeInSeconds) {
                        relaxNeighbor(current, drive, depTomorrow, arrTomorrow, true, bestArrivalTimes, queue);
                    }
                }
            }
        }

        // 5. BACKTRACKING
        if (finalState == null) return Stream.empty();

        LinkedList<RouteSegment> resultPath = new LinkedList<>();
        State cursor = finalState;

        while (cursor.parent != null) {
            Relationship r = cursor.viaRel;
            Node start = r.getStartNode();
            Node end = r.getEndNode();
            String tripId = (String) r.getProperty("trip_id");

            // Pobieramy surowe czasy z bazy do wyświetlenia
            String rawDep = (String) r.getProperty("dep_time");
            String rawArr = (String) r.getProperty("arr_time");

            // Jeśli to kurs "jutrzejszy", możemy to zaznaczyć w logach lub UI
            // W tej implementacji zwracamy surowe godziny GTFS, a flagę isNextDay ustawiamy na true

            resultPath.addFirst(new RouteSegment(
                    (String) start.getProperty("stop_name", "Unknown"),
                    (String) end.getProperty("stop_name", "Unknown"),
                    tripId,
                    rawDep,
                    rawArr,
                    tripId,
                    cursor.viaNextDay
            ));

            cursor = cursor.parent;
        }

        return resultPath.stream();
    }

    // --- Metoda pomocnicza do Dijkstry ---
    private void relaxNeighbor(State current, Relationship drive, int depTime, int arrTime, boolean isNextDay,
                               Map<Long, Integer> bestTimes, PriorityQueue<State> queue) {
        long neighborId = drive.getEndNode().getId();
        if (arrTime < bestTimes.getOrDefault(neighborId, Integer.MAX_VALUE)) {
            bestTimes.put(neighborId, arrTime);
            queue.add(new State(drive.getEndNode(), arrTime, current, drive, isNextDay));
        }
    }

    private String getNextDay(String day) {
        switch (day.toLowerCase()) {
            case "monday": return "tuesday";
            case "tuesday": return "wednesday";
            case "wednesday": return "thursday";
            case "thursday": return "friday";
            case "friday": return "saturday";
            case "saturday": return "sunday";
            case "sunday": return "monday";
            default: return "monday"; // Fallback
        }
    }

    private Node findNodeSmart(String identifier) {
        if (identifier == null) return null;
        Node node = tx.findNode(STOP_LABEL, "stop_id", identifier);
        if (node == null) {
            try {
                long idAsLong = Long.parseLong(identifier);
                node = tx.findNode(STOP_LABEL, "stop_id", idAsLong);
            } catch (NumberFormatException e) { }
        }
        if (node == null) node = tx.findNode(STOP_LABEL, "stop_code", identifier);
        if (node == null) {
            try {
                long idAsLong = Long.parseLong(identifier);
                node = tx.findNode(STOP_LABEL, "stop_code", idAsLong);
            } catch (NumberFormatException e) { }
        }
        return node;
    }

    private int parseTimeToSeconds(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) return Integer.MAX_VALUE;
        String[] parts = timeStr.split(":");
        if (parts.length < 2) return Integer.MAX_VALUE;
        try {
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            int s = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return h * 3600 + m * 60 + s;
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}