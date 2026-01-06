from neo4j import GraphDatabase
from pathlib import Path
import sys
from datetime import datetime

# ==========================================
# 1. KONFIGURACJA
# ==========================================
URI = "bolt://localhost:7687"
DATABASE = "warszawa"
USER = "neo4j"

PASSWORD_FILE = Path(__file__).resolve().parent / "password.txt"
try:
    with PASSWORD_FILE.open("r", encoding="utf-8") as f:
        PASSWORD = f.read().strip()
except FileNotFoundError:
    print(f"❌ BŁĄD: Nie znaleziono pliku 'password.txt'!")
    sys.exit(1)

AUTH = (USER, PASSWORD)

# ==========================================
# 2. ZAPYTANIA CYPHER
# ==========================================

# A. Pobranie idealnej trasy (ID oraz Nazwy)
QUERY_GET_IDEAL_PATH = """
    MATCH (start:Stop), (end:Stop)
    WHERE start.stop_name CONTAINS $start_name 
      AND end.stop_name = $end_name

    CALL gds.shortestPath.astar.stream('graf_miejski', {
        sourceNode: start,
        targetNode: end,
        latitudeProperty: 'stop_lat',
        longitudeProperty: 'stop_lon',
        relationshipWeightProperty: 'avg_time'
    })
    YIELD nodeIds, totalCost
    
    RETURN 
        nodeIds,
        totalCost,
        [nodeId IN nodeIds | gds.util.asNode(nodeId).stop_name] AS PathNames
"""

QUERY_GET_CANDIDATES = """
    WITH $current_time AS user_time,
         $current_stop AS current_stop_name,
         $remaining_path_ids AS input_node_ids

    // 1. Odtwarzamy fragment idealnej trasy z ID
    WITH [id IN input_node_ids | gds.util.asNode(id)] AS planned_path, 
         user_time, current_stop_name

    // 2. ZNAJDUJEMY START PO NAZWIE (To naprawia problem różnych słupków na starcie)
    MATCH (start_node:Stop) WHERE start_node.stop_name = current_stop_name

    // 3. Szukamy autobusów startujących z tego węzła
    MATCH (trip:Trip)-[r_start:STOPS_AT]->(start_node)
    WHERE r_start.departure_time >= user_time
      AND EXISTS { (trip)-[:VALID_ON]->(c:Calendar) WHERE c.monday = TRUE }

    // 4. Patrzymy dokąd jadą
    MATCH (trip)-[r_next:STOPS_AT]->(next_stop:Stop)
    WHERE r_next.stop_sequence > r_start.stop_sequence

    WITH planned_path, trip, r_start, next_stop, r_next 
    ORDER BY r_next.stop_sequence ASC

    // 5. Zwijamy trasę autobusu
    WITH planned_path, trip, r_start, collect(next_stop) AS actual_bus_path, collect(r_next) AS actual_times

    // 6. Liczymy zgodność (PORÓWNUJEMY PO NAZWACH, NIE PO ID!)
    WITH planned_path, trip, r_start, actual_bus_path, actual_times,
         reduce(acc = 0, i IN range(0, size(actual_bus_path)-1) |
            CASE 
                // Czy nie wyszliśmy poza zakres planu?
                WHEN (i + 1) < size(planned_path)
                 AND i = acc 
                 // --- KLUCZOWA POPRAWKA: Porównujemy nazwy, a nie obiekty ---
                 AND actual_bus_path[i].stop_name = planned_path[i+1].stop_name 
                THEN acc + 1
                ELSE acc
            END
         ) AS match_count

    WHERE match_count > 0

    // 7. Wynik
    RETURN 
        trip.trip_headsign AS Linia,
        r_start.departure_time AS Czas_Odjazdu,
        match_count AS Ile_Zgodnych,
        actual_bus_path[match_count-1].stop_name AS Wysiadka_Na,
        actual_times[match_count-1].arrival_time AS Czas_Przyjazdu
    ORDER BY match_count DESC, Czas_Odjazdu ASC
    LIMIT 10;
"""

# ==========================================
# 3. LOGIKA
# ==========================================

def calculate_score(candidate, current_time_str):
    now = datetime.strptime(current_time_str, "%H:%M:%S")
    dep = datetime.strptime(candidate['Czas_Odjazdu'], "%H:%M:%S")
    
    wait_minutes = (dep - now).total_seconds() / 60
    stops_covered = candidate['Ile_Zgodnych']
    
    # 50 punktów za przystanek, -1 punkt za minutę czekania
    return (stops_covered * 50) - (wait_minutes * 1)

def main():
    start_stop = "Wagonownia"
    final_dest = "Główna"
    current_time = "08:45:00"

    print(f"🔌 Łączę z bazą '{DATABASE}'...")

    with GraphDatabase.driver(URI, auth=AUTH) as driver:
        with driver.session(database=DATABASE) as session:
            
            # --- KROK 1: PLANOWANIE (A*) ---
            print(f"🌎 Generuję idealną trasę z '{start_stop}' do '{final_dest}'...")
            path_result = session.run(QUERY_GET_IDEAL_PATH, 
                                      start_name=start_stop, 
                                      end_name=final_dest).single()
            
            if not path_result:
                print("❌ Nie znaleziono trasy w grafie.")
                return

            full_path_ids = path_result["nodeIds"]
            path_names = path_result["PathNames"] # <--- TU MAMY NAZWY
            total_cost = path_result["totalCost"]

            # --- WYŚWIETLENIE CAŁEJ TRASY ---
            print("-" * 60)
            print(f"🗺️  PLAN PODRÓŻY (A*)")
            print(f"   Szacowany czas (wagi grafu): {total_cost:.1f} min")
            print(f"   Liczba przystanków: {len(full_path_ids)}")
            print("-" * 60)
            
            for i, name in enumerate(path_names):
                prefix = "   ●"
                if i == 0: prefix = "🟢 START"
                elif i == len(path_names)-1: prefix = "🏁 CEL  "
                print(f"{prefix} {name}")
            
            print("-" * 60 + "\n")

            # --- KROK 2: SYMULACJA ---
            current_stop_name = start_stop
            current_path_index = 0
            step = 1

            while current_stop_name != final_dest:
                print(f"📍 [{current_time}] Jestem na: {current_stop_name}")
                
                remaining_ids = full_path_ids[current_path_index:]
                
                # Pobieramy kandydatów
                candidates = session.run(QUERY_GET_CANDIDATES,
                                         current_time=current_time,
                                         current_stop=current_stop_name,
                                         remaining_path_ids=remaining_ids).data()

                if not candidates:
                    print("❌ Utknąłem. Brak autobusów pokrywających się z wyznaczoną trasą.")
                    break

                # Wybór najlepszego
                best_candidate = None
                best_score = -float('inf')

                for cand in candidates:
                    score = calculate_score(cand, current_time)
                    if score > best_score:
                        best_score = score
                        best_candidate = cand

                if best_candidate is None:
                     print("⚠️ Błąd algorytmu wyboru.")
                     break

                # Wykonanie ruchu
                linia = best_candidate["Linia"]
                odjazd = best_candidate["Czas_Odjazdu"]
                wysiadka = best_candidate["Wysiadka_Na"]
                przyjazd = best_candidate["Czas_Przyjazdu"]
                zgodnosc = best_candidate["Ile_Zgodnych"]
                
                print(f"👉 WYBÓR: Linia {linia}")
                print(f"   🕒 Czekasz do {odjazd}")
                print(f"   🚌 Jedziesz {zgodnosc} przystanków")
                print(f"   ⬇️ Wysiadasz na: {wysiadka} o godzinie {przyjazd}")
                print("-" * 50)

                current_stop_name = wysiadka
                current_time = przyjazd
                current_path_index += zgodnosc 
                
                step += 1
                if step > 20: 
                    print("⚠️ Przekroczono limit przesiadek!")
                    break

            if current_stop_name == final_dest:
                print("\n🎉 SUKCES! Dotarłeś do celu.")

if __name__ == "__main__":
    main()