| Typ Indeksu | Składnia Cypher | Komentarz Ekspercki |
| :--- | :--- | :--- |
| **Range Index** | `CREATE INDEX idx_person_dob IF NOT EXISTS FOR (n:Person) ON (n.born)` | Podstawowy indeks dla równości i zakresów. Nazwa (`idx_person_dob`) jest kluczowa dla późniejszego zarządzania i hintowania. |
| **Composite Index** | `CREATE INDEX idx_person_full IF NOT EXISTS FOR (n:Person) ON (n.lastname, n.firstname)` | **Kolejność pól ma znaczenie.** Indeks działa dla `lastname` oraz `lastname` + `firstname`, ale nie dla samego `firstname`. |
| **Text Index** | `CREATE TEXT INDEX idx_desc_text IF NOT EXISTS FOR (n:Product) ON (n.description)` | Dedykowany dla predykatów łańcuchowych: `CONTAINS` i `ENDS WITH`. |
| **Point Index** | `CREATE POINT INDEX idx_loc IF NOT EXISTS FOR (n:Place) ON (n.coords)` | Obsługuje typy przestrzenne. Możliwe dodanie `OPTIONS` z konfiguracją **BBox**. |
| **Full-text Index** | `CREATE FULLTEXT INDEX idx_ft_content FOR (n:Article|Post) ON EACH [n.title, n.body]` | Indeks pełnotekstowy (Lucene). Wymaga specyficznej składni `ON EACH` przy wielu polach. |
| **Vector Index** | `CREATE VECTOR INDEX idx_vec FOR (n:Doc) ON (n.embedding) OPTIONS {indexConfig: {vector.dimensions: 1536, vector.similarity_function: 'cosine'}}` | Wymaga podania konfiguracji wymiarowości i funkcji podobieństwa w mapie `OPTIONS`. Niezbędny do RAG / GenAI. |