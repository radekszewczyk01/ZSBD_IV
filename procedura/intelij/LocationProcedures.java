package org.example;

import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;

import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

public class LocationProcedures {

    @Context
    public Transaction tx;

    // Klasa wyjściowa dla procedury
    public static class GeoResult {
        public Double latitude;
        public Double longitude;

        public GeoResult(Double latitude, Double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    // Używam Mode.READ, ponieważ tylko czytamy dane o przystankach i obliczamy wynik.
    // Nie zapisujemy nic do bazy w tym momencie.
    @Procedure(name = "custom.getRandomLocation", mode = Mode.READ)
    public Stream<GeoResult> getRandomLocation() {

        // 1. Zapytanie wyciągające skrajne wartości z bazy
        String query = "MATCH (s:Stop) " +
                "RETURN min(s.stop_lat) as minLat, max(s.stop_lat) as maxLat, " +
                "       min(s.stop_lon) as minLon, max(s.stop_lon) as maxLon";

        try (Result result = tx.execute(query)) {
            if (result.hasNext()) {
                Map<String, Object> row = result.next();

                // Pobieramy wartości. Używamy rzutowania na Number, aby obsłużyć
                // zarówno Float jak i Double (bezpieczeństwo typów w Neo4j).
                // Jeśli baza jest pusta, te wartości mogą być null - warto to obsłużyć w produkcji.
                double minLat = ((Number) row.get("minLat")).doubleValue();
                double maxLat = ((Number) row.get("maxLat")).doubleValue();
                double minLon = ((Number) row.get("minLon")).doubleValue();
                double maxLon = ((Number) row.get("maxLon")).doubleValue();

                // 2. Matematyka losująca
                Random r = new Random();

                // Wzór: min + (max - min) * losowa_0_1
                double randomLat = minLat + (maxLat - minLat) * r.nextDouble();
                double randomLon = minLon + (maxLon - minLon) * r.nextDouble();

                // 3. Zwracamy wynik
                return Stream.of(new GeoResult(randomLat, randomLon));
            }
        }

        // Jeśli nie znaleziono przystanków (pusta baza), zwracamy pusty strumień
        return Stream.empty();
    }
}