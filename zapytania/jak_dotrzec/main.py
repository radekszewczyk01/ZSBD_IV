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
    YIELD nodeIds
    RETURN nodeIds
"""

QUERY_GET_PATH_NAMES = """
    UNWIND $node_ids AS nid
    RETURN gds.util.asNode(nid).stop_name AS name
"""

QUERY_GET_CANDIDATES = """
    WITH $current_time AS user_time,
         $current_stop AS current_stop_name,
         $remaining_path_ids AS input_node_ids

    WITH [id IN input_node_ids | gds.util.asNode(id)] AS planned_path, 
         user_time, current_stop_name

    WITH planned_path, user_time, planned_path[0] AS start_node

    MATCH (trip:Trip)-[r_start:STOPS_AT]->(start_node)
    WHERE r_start.departure_time >= user_time
      AND EXISTS { (trip)-[:VALID_ON]->(c:Calendar) WHERE c.monday = TRUE }

    MATCH (trip)-[r_next:STOPS_AT]->(next_stop:Stop)
    WHERE r_next.stop_sequence > r_start.stop_sequence

    WITH planned_path, trip, r_start, next_stop, r_next 
    ORDER BY r_next.stop_sequence ASC

    WITH planned_path, trip, r_start, collect(next_stop) AS actual_bus_path, collect(r_next) AS actual_times

    WITH planned_path, trip, r_start, actual_bus_path, actual_times,
         reduce(acc = 0, i IN range(0, size(actual_bus_path)-1) |
            CASE 
                WHEN (i + 1) < size(planned_path)
                 AND i = acc 
                 AND actual_bus_path[i] = planned_path[i+1] 
                THEN acc + 1
                ELSE acc
            END
         ) AS match_count

    WHERE match_count > 0

    RETURN 
        trip.trip_headsign AS Linia,
        r_start.departure_time AS Czas_Odjazdu,
        match_count AS Ile_Zgodnych,
        actual_bus_path[match_count-1].stop_name AS Wysiadka_Na,
        actual_times[match_count-1].arrival_time AS Czas_Przyjazdu
    ORDER BY match_count DESC, Czas_Odjazdu ASC
    LIMIT 20; 
"""

# ==========================================
# 3. LOGIKA
# ==========================================

def calculate_score(candidate, current_time_str):
    now = datetime.strptime(current_time_str, "%H:%M:%S")
    dep = datetime.strptime(candidate['Czas_Odjazdu'], "%H:%M:%S")
    
    wait_minutes = (dep - now).total_seconds() / 60
    stops_covered = candidate['Ile_Zgodnych']
    
    # Nasz algorytm wagowy
    return (stops_covered * 50) - (wait_minutes * 1)

def main():
    start_stop = "Wagonownia"
    final_dest = "Główna"
    current_time = "08:45:00"

    print(f"🔌 Łączę z bazą '{DATABASE}'...")

    with GraphDatabase.driver(URI, auth=AUTH) as driver:
        with driver.session(database=DATABASE) as session:
            
            # --- PLANOWANIE ---
            print(f"🌎 Generuję idealną trasę z '{start_stop}' do '{final_dest}'...")
            path_result = session.run(QUERY_GET_IDEAL_PATH, 
                                      start_name=start_stop, 
                                      end_name=final_dest).single()
            
            if not path_result:
                print("❌ Nie znaleziono trasy.")
                return

            full_path_ids = path_result["nodeIds"]
            print(f"✅ Trasa wyznaczona! Liczba przystanków: {len(full_path_ids)}\n")

            # --- SYMULACJA ---
            current_stop_name = start_stop
            current_path_index = 0
            step = 1

            while current_stop_name != final_dest:
                print(f"📍 ETAP {step} | Czas: {current_time} | Przystanek: {current_stop_name}")
                
                remaining_ids = full_path_ids[current_path_index:]
                
                # --- DEBUG: Pokaż remaining_ids ---
                print(f"   🐛 Remaining IDs (len) {len(remaining_ids)} (pierwsze 10): {remaining_ids[:10]}...")
                
                candidates = session.run(QUERY_GET_CANDIDATES,
                                         current_time=current_time,
                                         current_stop=current_stop_name,
                                         remaining_path_ids=remaining_ids).data()

                if not candidates:
                    print("❌ Brak połączeń. Koniec.")
                    break

                # --- DEBUG: Znajdź MAX dystans ---
                # Sortujemy po liczbie przystanków malejąco
                max_dist_candidate = sorted(candidates, key=lambda x: x['Ile_Zgodnych'], reverse=True)[0]
                
                print(f"   🏆 Opcja MAX DYSTANS: {max_dist_candidate['Linia']}")
                print(f"      Zasięg: {max_dist_candidate['Ile_Zgodnych']} przystanków")
                print(f"      Odjazd: {max_dist_candidate['Czas_Odjazdu']} -> Przyjazd: {max_dist_candidate['Czas_Przyjazdu']}")

                # --- WYBÓR ALGORYTMU ---
                best_candidate = None
                best_score = -float('inf')

                for cand in candidates:
                    score = calculate_score(cand, current_time)
                    if score > best_score:
                        best_score = score
                        best_candidate = cand

                if best_candidate is None: break

                print(f"   🧠 Opcja ALGORYTMU:   {best_candidate['Linia']}")
                print(f"      Zasięg: {best_candidate['Ile_Zgodnych']} przystanków")
                print(f"      Odjazd: {best_candidate['Czas_Odjazdu']} -> Przyjazd: {best_candidate['Czas_Przyjazdu']}")
                
                # Porównanie
                if max_dist_candidate == best_candidate:
                    print("   ✅ Algorytm wybrał opcję o maksymalnym zasięgu.")
                else:
                    print("   ⚠️ Algorytm poświęcił zasięg dla lepszego czasu.")

                print("-" * 60)

                # Wykonanie ruchu
                linia = best_candidate["Linia"]
                odjazd = best_candidate["Czas_Odjazdu"]
                wysiadka = best_candidate["Wysiadka_Na"]
                przyjazd = best_candidate["Czas_Przyjazdu"]
                zgodnosc = best_candidate["Ile_Zgodnych"]
                
                current_stop_name = wysiadka
                current_time = przyjazd
                current_path_index += zgodnosc 
                
                step += 1
                
                # --- OGRANICZENIE DO 2 ITERACJI ---
                if step > 20: 
                    print("🛑 Koniec debugowania (limit 2 kroków osiągnięty).")
                    break

            if current_stop_name == final_dest:
                print("\n🎉 SUKCES! Dotarłeś do celu.")

if __name__ == "__main__":
    main()