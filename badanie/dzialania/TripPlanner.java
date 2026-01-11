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
    // --- ZMIANA 1: Nowy typ relacji ---
    private static final RelationshipType WALK = RelationshipType.withName("WALK");

    private static final Label STOP_LABEL = Label.label("Stop");
    private static final Label TRIP_LABEL = Label.label("Trip");
    private static final int DAY_SECONDS = 24 * 3600;

    public static class JourneyLeg {
        public String line;
        public String startStop;
        public String depTime;
        public String endStop;
        public String arrTime;
        public long stopsCount;
        public boolean isNextDay;

        public JourneyLeg(String line, String startStop, String depTime, String endStop, String arrTime, long stopsCount, boolean isNextDay) {
            this.line = line;
            this.startStop = startStop;
            this.depTime = depTime;
            this.endStop = endStop;
            this.arrTime = arrTime;
            this.stopsCount = stopsCount;
            this.isNextDay = isNextDay;
        }
    }

    private static class State implements Comparable<State> {
        Node node;
        int timeInSeconds;
        State parent;
        Relationship viaRel;
        boolean viaNextDay;

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

    private static class RawSegment {
        String startStop;
        String endStop;
        String tripId; // Może być "WALK"
        String depTime;
        String arrTime;
        boolean isNextDay;

        public RawSegment(String startStop, String endStop, String tripId, String depTime, String arrTime, boolean isNextDay) {
            this.startStop = startStop;
            this.endStop = endStop;
            this.tripId = tripId;
            this.depTime = depTime;
            this.arrTime = arrTime;
            this.isNextDay = isNextDay;
        }
    }

    @Procedure(name = "custom.findFastestRoute", mode = Mode.READ)
    public Stream<JourneyLeg> findFastestRoute(
            @Name("startStopCode") String startStopCode,
            @Name("endStopCode") String endStopCode,
            @Name("startTime") String startTimeStr,
            @Name("dayOfWeek") String dayOfWeek
    ) {
        Node startNode = findNodeSmart(startStopCode);
        Node endNode = findNodeSmart(endStopCode);

        if (startNode == null || endNode == null) return Stream.empty();

        // Optymalizacja: Parsowanie czasu raz
        int startTimeSeconds = parseTimeToSeconds(startTimeStr);
        String todayProp = dayOfWeek.toLowerCase();
        String tomorrowProp = getNextDay(todayProp);

        Set<String> tripsToday = new HashSet<>();
        Set<String> tripsTomorrow = new HashSet<>();
        fetchActiveTrips(todayProp, tomorrowProp, tripsToday, tripsTomorrow);

        PriorityQueue<State> queue = new PriorityQueue<>();
        Map<Long, Integer> bestArrivalTimes = new HashMap<>();

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

            // --- A. Logika dla AUTOBUSÓW (DRIVE) ---
            for (Relationship drive : current.node.getRelationships(Direction.OUTGOING, DRIVE)) {
                Object depObj = drive.getProperty("dep_time_sec", null);
                Object arrObj = drive.getProperty("arr_time_sec", null);

                // Fallback do parsowania stringów jeśli nie zrobiono migracji na int
                int depRaw, arrRaw;
                if (depObj instanceof Number) {
                    depRaw = ((Number) depObj).intValue();
                    arrRaw = ((Number) arrObj).intValue();
                } else {
                    // Support dla starego modelu (String)
                    depRaw = parseTimeToSeconds((String) drive.getProperty("dep_time"));
                    arrRaw = parseTimeToSeconds((String) drive.getProperty("arr_time"));
                }

                String tripId = (String) drive.getProperty("trip_id");

                if (tripsToday.contains(tripId)) {
                    if (depRaw >= current.timeInSeconds) {
                        relaxNeighbor(current, drive, depRaw, arrRaw, false, bestArrivalTimes, queue);
                    }
                }
                if (tripsTomorrow.contains(tripId)) {
                    int depTomorrow = depRaw + DAY_SECONDS;
                    int arrTomorrow = arrRaw + DAY_SECONDS;
                    if (depTomorrow >= current.timeInSeconds) {
                        relaxNeighbor(current, drive, depTomorrow, arrTomorrow, true, bestArrivalTimes, queue);
                    }
                }
            }

            // --- B. ZMIANA 2: Logika dla PIESZYCH (WALK) ---
            for (Relationship walk : current.node.getRelationships(Direction.OUTGOING, WALK)) {
                // Pobieramy czas przejścia (domyślnie 60s jeśli brak property)
                int duration = ((Number) walk.getProperty("time_sec", 60)).intValue();

                // Spacer zaczynamy natychmiast po przyjeździe
                int walkStartTime = current.timeInSeconds;
                int walkEndTime = walkStartTime + duration;

                // Sprawdzamy czy spacer nie przekracza północy (rzadkie, ale możliwe)
                boolean isNextDay = walkEndTime >= DAY_SECONDS && !current.viaNextDay;
                // Uproszczenie: w tym modelu viaNextDay w State odnosi się do segmentu dojazdowego,
                // tutaj po prostu dodajemy czas.

                long neighborId = walk.getEndNode().getId();
                if (walkEndTime < bestArrivalTimes.getOrDefault(neighborId, Integer.MAX_VALUE)) {
                    bestArrivalTimes.put(neighborId, walkEndTime);
                    queue.add(new State(walk.getEndNode(), walkEndTime, current, walk, isNextDay));
                }
            }
        }

        if (finalState == null) return Stream.empty();
        return collapsePath(finalState);
    }

    private Stream<JourneyLeg> collapsePath(State finalState) {
        LinkedList<RawSegment> rawSegments = new LinkedList<>();
        State cursor = finalState;

        while (cursor.parent != null) {
            Relationship r = cursor.viaRel;
            Node start = r.getStartNode();
            Node end = r.getEndNode();
            String startName = (String) start.getProperty("stop_name", "Unknown");
            String endName = (String) end.getProperty("stop_name", "Unknown");

            // --- Rozróżnienie DRIVE vs WALK przy odtwarzaniu ---
            if (r.isType(DRIVE)) {
                String tripId = (String) r.getProperty("trip_id");
                // Obsługa String/Int dla czasu (dla kompatybilności)
                Object depVal = r.getProperty("dep_time_sec", null);
                String rawDep = (depVal != null) ? formatSeconds((Number) depVal) : (String) r.getProperty("dep_time");

                Object arrVal = r.getProperty("arr_time_sec", null);
                String rawArr = (arrVal != null) ? formatSeconds((Number) arrVal) : (String) r.getProperty("arr_time");

                rawSegments.addFirst(new RawSegment(startName, endName, tripId, rawDep, rawArr, cursor.viaNextDay));
            } else if (r.isType(WALK)) {
                // Dla spaceru wyliczamy czasy na podstawie stanu Dijkstry
                // Czas przybycia na koniec spaceru:
                String arrTimeStr = formatSeconds(cursor.timeInSeconds);
                // Czas rozpoczęcia spaceru (z węzła rodzica):
                String depTimeStr = formatSeconds(cursor.parent.timeInSeconds);

                // Używamy specjalnego ID "WALK"
                rawSegments.addFirst(new RawSegment(startName, endName, "WALK", depTimeStr, arrTimeStr, cursor.viaNextDay));
            }
            cursor = cursor.parent;
        }

        if (rawSegments.isEmpty()) return Stream.empty();

        // --- ZMIANA 3: Grupowanie segmentów (Bus vs Walk) ---
        List<JourneyLeg> journey = new ArrayList<>();
        RawSegment current = rawSegments.get(0);

        String legStart = current.startStop;
        String legStartTime = current.depTime;
        String legTripId = current.tripId;
        boolean legNextDay = current.isNextDay;
        int stops = 1;

        for (int i = 1; i < rawSegments.size(); i++) {
            RawSegment next = rawSegments.get(i);

            // Czy kontynuujemy ten sam etap?
            // Warunek: Ten sam trip_id ORAZ to nie jest spacer (spacery zawsze są osobnymi segmentami lub łączone inaczej)
            // Tutaj założenie: Spacer zawsze przerywa jazdę autobusem.
            boolean isSameTrip = next.tripId.equals(legTripId) && !legTripId.equals("WALK");

            if (isSameTrip) {
                stops++;
            } else {
                // Zamykamy stary etap
                RawSegment legEndSeg = rawSegments.get(i - 1);

                String lineName;
                if (legTripId.equals("WALK")) {
                    lineName = "Spacer (Przesiadka)";
                    // Dla spaceru stopsCount może oznaczać liczbę odcinków (zwykle 1)
                } else {
                    lineName = getTripHeadsign(legTripId);
                }

                journey.add(new JourneyLeg(lineName, legStart, legStartTime, legEndSeg.endStop, legEndSeg.arrTime, stops, legNextDay));

                // Startujemy nowy
                legTripId = next.tripId;
                legStart = next.startStop;
                legStartTime = next.depTime;
                legNextDay = next.isNextDay;
                stops = 1;
            }
        }

        // Ostatni segment
        RawSegment lastSeg = rawSegments.getLast();
        String lineName = legTripId.equals("WALK") ? "Spacer (Przesiadka)" : getTripHeadsign(legTripId);

        journey.add(new JourneyLeg(lineName, legStart, legStartTime, lastSeg.endStop, lastSeg.arrTime, stops, legNextDay));

        return journey.stream();
    }

    // --- Metody pomocnicze ---

    private String formatSeconds(Number secondsObj) {
        int totalSec = secondsObj.intValue();
        // Obsługa przechodzenia przez północ dla wyświetlania (opcjonalne, tutaj proste modulo)
        // totalSec = totalSec % (24 * 3600);
        int h = totalSec / 3600;
        int m = (totalSec % 3600) / 60;
        int s = totalSec % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    // ... (Reszta metod pomocniczych: getTripHeadsign, fetchActiveTrips, relaxNeighbor, findNodeSmart, parseTimeToSeconds bez zmian) ...
    // Pamiętaj, aby skopiować je z poprzedniej wersji!

    private String getTripHeadsign(String tripId) {
        Node tripNode = tx.findNode(TRIP_LABEL, "trip_id", tripId);
        if (tripNode != null) {
            String headsign = (String) tripNode.getProperty("trip_headsign", "");
            return tripId + " (Kier: " + headsign + ")";
        }
        return tripId;
    }

    private void fetchActiveTrips(String today, String tomorrow, Set<String> todaySet, Set<String> tomorrowSet) {
        try (ResourceIterator<Node> trips = tx.findNodes(TRIP_LABEL)) {
            while (trips.hasNext()) {
                Node trip = trips.next();
                Relationship validRel = trip.getSingleRelationship(VALID_ON, Direction.OUTGOING);
                if (validRel != null) {
                    Node cal = validRel.getEndNode();
                    String tId = (String) trip.getProperty("trip_id");
                    if ((boolean) cal.getProperty(today, false)) todaySet.add(tId);
                    if ((boolean) cal.getProperty(tomorrow, false)) tomorrowSet.add(tId);
                }
            }
        }
    }

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
            default: return "monday";
        }
    }

    private Node findNodeSmart(String identifier) {
        if (identifier == null) return null;
        Node node = tx.findNode(STOP_LABEL, "stop_id", identifier);
        if (node == null) {
            try {
                long id = Long.parseLong(identifier);
                node = tx.findNode(STOP_LABEL, "stop_id", id);
            } catch (Exception e) {}
        }
        if (node == null) node = tx.findNode(STOP_LABEL, "stop_code", identifier);
        if (node == null) {
            try {
                long id = Long.parseLong(identifier);
                node = tx.findNode(STOP_LABEL, "stop_code", id);
            } catch (Exception e) {}
        }
        return node;
    }

    private int parseTimeToSeconds(String timeStr) {
        if (timeStr == null) return Integer.MAX_VALUE;
        String[] parts = timeStr.split(":");
        if (parts.length < 2) return Integer.MAX_VALUE;
        int h = Integer.parseInt(parts[0]);
        int m = Integer.parseInt(parts[1]);
        int s = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
        return h * 3600 + m * 60 + s;
    }
}