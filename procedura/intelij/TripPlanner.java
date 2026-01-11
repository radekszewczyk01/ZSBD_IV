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
    private static final int DAY_SECONDS = 24 * 3600;

    // --- NOWA KLASA WYNIKOWA: ETAP PODRÓŻY ---
    // Zwraca skondensowane informacje: Wsiądź -> Jedź -> Wysiądź
    public static class JourneyLeg {
        public String line;         // Np. "143 (-> Rembertów)"
        public String startStop;    // Gdzie wsiadamy
        public String depTime;      // O której wsiadamy
        public String endStop;      // Gdzie wysiadamy
        public String arrTime;      // O której wysiadamy
        public long stopsCount;     // Ile przystanków przejechaliśmy
        public boolean isNextDay;   // Czy ten etap kończy się następnego dnia

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

    // Struktura pomocnicza do Dijkstry
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

    // Struktura pomocnicza do "surowej" ścieżki przed zgrupowaniem
    private static class RawSegment {
        String startStop;
        String endStop;
        String tripId;
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
        // 1. Walidacja węzłów
        Node startNode = findNodeSmart(startStopCode);
        Node endNode = findNodeSmart(endStopCode);

        if (startNode == null || endNode == null) {
            log.error("TripPlanner: Nie znaleziono przystanków.");
            return Stream.empty();
        }

        int startTimeSeconds = parseTimeToSeconds(startTimeStr);
        String todayProp = dayOfWeek.toLowerCase();
        String tomorrowProp = getNextDay(todayProp);

        // 2. Pre-fetching aktywnych tripów
        Set<String> tripsToday = new HashSet<>();
        Set<String> tripsTomorrow = new HashSet<>();
        fetchActiveTrips(todayProp, tomorrowProp, tripsToday, tripsTomorrow);

        // 3. Algorytm Dijkstry
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

            for (Relationship drive : current.node.getRelationships(Direction.OUTGOING, DRIVE)) {
                String tripId = (String) drive.getProperty("trip_id");
                Object depObj = drive.getProperty("dep_time");
                Object arrObj = drive.getProperty("arr_time");
                if (depObj == null || arrObj == null) continue;

                int depRaw = parseTimeToSeconds(depObj.toString());
                int arrRaw = parseTimeToSeconds(arrObj.toString());

                // Sprawdź Dziś
                if (tripsToday.contains(tripId)) {
                    if (depRaw >= current.timeInSeconds) {
                        relaxNeighbor(current, drive, depRaw, arrRaw, false, bestArrivalTimes, queue);
                    }
                }
                // Sprawdź Jutro
                if (tripsTomorrow.contains(tripId)) {
                    int depTomorrow = depRaw + DAY_SECONDS;
                    int arrTomorrow = arrRaw + DAY_SECONDS;
                    if (depTomorrow >= current.timeInSeconds) {
                        relaxNeighbor(current, drive, depTomorrow, arrTomorrow, true, bestArrivalTimes, queue);
                    }
                }
            }
        }

        if (finalState == null) return Stream.empty();

        // 4. Rekonstrukcja i Grupowanie (Collapsing)
        return collapsePath(finalState);
    }

    /**
     * Ta metoda zamienia ciąg atomowych segmentów (A->B, B->C, C->D) w logiczne etapy (A->D linią X).
     */
    private Stream<JourneyLeg> collapsePath(State finalState) {
        LinkedList<RawSegment> rawSegments = new LinkedList<>();
        State cursor = finalState;

        // A. Odtwarzamy surową ścieżkę od tyłu
        while (cursor.parent != null) {
            Relationship r = cursor.viaRel;
            Node start = r.getStartNode();
            Node end = r.getEndNode();
            String tripId = (String) r.getProperty("trip_id");
            String rawDep = (String) r.getProperty("dep_time");
            String rawArr = (String) r.getProperty("arr_time");

            String startName = (String) start.getProperty("stop_name", "Unknown");
            String endName = (String) end.getProperty("stop_name", "Unknown");

            rawSegments.addFirst(new RawSegment(startName, endName, tripId, rawDep, rawArr, cursor.viaNextDay));
            cursor = cursor.parent;
        }

        if (rawSegments.isEmpty()) return Stream.empty();

        // B. Grupujemy segmenty w logiczne etapy
        List<JourneyLeg> journey = new ArrayList<>();

        // Inicjalizacja pierwszego etapu
        RawSegment firstSeg = rawSegments.getFirst();
        String currentTripId = firstSeg.tripId;
        String legStartStop = firstSeg.startStop;
        String legDepTime = firstSeg.depTime;
        boolean legIsNextDay = firstSeg.isNextDay;
        long stopsCounter = 0;

        // Iterujemy przez resztę
        for (RawSegment seg : rawSegments) {
            if (seg.tripId.equals(currentTripId)) {
                // Kontynuujemy podróż tym samym autobusem
                stopsCounter++;
            } else {
                // PRZESIADKA! Zamykamy poprzedni etap
                String headsign = getTripHeadsign(currentTripId); // Pobieramy kierunek z bazy
                // Koniec poprzedniego etapu to start obecnego segmentu (seg.startStop) lub koniec poprzedniego
                // Ale logicznie koniec poprzedniego segmentu w pętli to seg.startStop (bo ciągłość)
                // Musimy wziąć czas przyjazdu z OSTATNIEGO segmentu starego tripa.
                // Tu dla uproszczenia w pętli for-each jest trudno dostać "previous".
                // Lepiej użyć pętli for(int i...).
            }
        }

        // --- Podejście C: Pętla klasyczna dla łatwiejszego dostępu do indeksów ---
        journey.clear();
        if (rawSegments.isEmpty()) return Stream.empty();

        RawSegment current = rawSegments.get(0);

        // Zmienne tymczasowe trwającegu etapu
        String legStart = current.startStop;
        String legStartTime = current.depTime;
        String legTripId = current.tripId;
        boolean legNextDay = current.isNextDay;
        int stops = 1;

        for (int i = 1; i < rawSegments.size(); i++) {
            RawSegment next = rawSegments.get(i);

            if (next.tripId.equals(legTripId)) {
                // Ten sam autobus -> przedłużamy etap
                stops++;
            } else {
                // Zmiana autobusu -> Zapisujemy stary etap
                RawSegment legEndSeg = rawSegments.get(i - 1); // Ostatni segment starego etapu
                String fullHeadsign = getTripHeadsign(legTripId);

                journey.add(new JourneyLeg(
                        fullHeadsign,
                        legStart,
                        legStartTime,
                        legEndSeg.endStop,
                        legEndSeg.arrTime,
                        stops,
                        legNextDay
                ));

                // Rozpoczynamy nowy etap
                legTripId = next.tripId;
                legStart = next.startStop;
                legStartTime = next.depTime;
                legNextDay = next.isNextDay;
                stops = 1;
            }
        }

        // Zapisujemy ostatni etap (który nie został zamknięty w pętli)
        RawSegment lastSeg = rawSegments.getLast();
        String fullHeadsign = getTripHeadsign(legTripId);

        journey.add(new JourneyLeg(
                fullHeadsign,
                legStart,
                legStartTime,
                lastSeg.endStop,
                lastSeg.arrTime,
                stops,
                legNextDay
        ));

        return journey.stream();
    }

    // --- Metody pomocnicze ---

    private String getTripHeadsign(String tripId) {
        // Szukamy węzła Trip, żeby pobrać jego headsign
        Node tripNode = tx.findNode(TRIP_LABEL, "trip_id", tripId);
        if (tripNode != null) {
            String headsign = (String) tripNode.getProperty("trip_headsign", "");
            // Opcjonalnie: można dodać też route_id jeśli jest
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

    // --- Pozostałe metody (getNextDay, findNodeSmart, parseTimeToSeconds) bez zmian ---
    // (Skopiuj je z poprzedniej wersji lub zostaw jeśli są w klasie)

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