package org.example;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.util.stream.Stream;

public class GuestProcedures {

    // To daje nam dostęp do bazy danych wewnątrz procedury
    @Context
    public Transaction tx;

    // Klasa pomocnicza, żeby zwrócić wynik (np. ID utworzonego węzła)
    public static class Output {
        public String nodeId;

        public Output(String nodeId) {
            this.nodeId = nodeId;
        }
    }

    // Definicja procedury. Mode.WRITE jest konieczne, bo zmieniamy dane!
    @Procedure(name = "custom.createGuest", mode = Mode.WRITE)
    public Stream<Output> createGuest(@Name("imie") String imie, @Name("id") String id) {

        // 1. Tworzymy węzeł z Label "Guest"
        Node node = tx.createNode(Label.label("Guest"));

        // 2. Ustawiamy właściwości
        node.setProperty("name", imie);
        node.setProperty("guest_id", id);

        // 3. Zwracamy wynik (stream z jednym elementem)
        return Stream.of(new Output(id));
    }
}