# Fieldnotes Asset Intelligence Copilot

Fieldnotes is a local chat app for exploring oil-field records. Ask questions in plain language, and the copilot searches the selected dataset, applies its manuals and procedures, calculates results, and shows the records behind its answer. You can import different datasets, edit records with an audit trail, and return to saved conversations.

The sample field data is synthetic. Its manuals and procedures are fictional examples, not real operating or legal guidance.

## What it can do

- Answer questions about wells, inspections, production status, downtime, spills, maintenance, regulator events, notes and costs.
- Search operating manuals, SOPs and free-text maintenance reports. Some important details, such as repair timing, exist only in these reports.
- Combine records and documents for questions such as whether a spill met its containment deadline.
- Show results as a table, card, chart, map or heatmap.
- Import datasets from ZIP files, including datasets with additional or missing JSON collections.
- Keep conversation transcripts and database edits so users can review them later.

The system can only calculate a measure when the selected dataset contains the needed evidence. For example, if it has no oil-production volumes, it cannot identify the highest-volume producer. It may be able to show related inspection-based producing status, but that is not oil volume or production rate. Answers about safety or compliance should be checked against their cited source records and procedures.

## How it works

```text
ZIP dataset
   ├── JSON and JSONL records ──> Local H2 SQL database
   └── Manuals, SOPs, reports ──> Local document search index
                                      │
Question ──> Ollama model chooses search/calculation tools
                                      │
                 Records + documents + notes + saved context
                                      │
             Evidence-based answer + citations + visual
```

### Import and storage

On the first start, the sample data is loaded into a local H2 database. Use **Import ZIP** in the app to add another dataset. The importer searches folders recursively, so the ZIP does not have to use one exact folder layout. It accepts JSON arrays, wrapped arrays, individual JSON objects and JSON Lines (`.jsonl`). Optional collections can be missing. Unknown record types and extra fields are retained as JSON instead of being discarded. Malformed rows are skipped so that one bad row does not cancel the entire import; a local operator is added if a dataset contains no users.

Each imported database is stored separately. Standard records are indexed by entity type and record ID, with well ID and event date available for common lookups. The database retains the original record JSON, including fields the copilot does not know in advance. Manuals, SOPs and reports remain as files beside the imported database.

### Search and calculations

When a database is selected, its records are loaded into the copilot's working data model. The model can call purpose-built tools for common questions, such as current well status, inspection history, downtime, confirmed spills, spill containment compliance, repair history, regulatory events and pressure evidence. These tools calculate values in code so that the model does not have to do the arithmetic itself.

For a collection or field the app does not recognize, **Dataset Catalog** discovers the available record types and field paths. **Query Records** can filter by well, area, dates or field values, sort records, group them and calculate counts, sums or averages. **Search Everywhere** looks across all imported record types, areas and indexed documents when the question does not point to a known collection.

Documents are split into sections at Markdown headings and searched with a lightweight BM25 text index. This lets a question combine structured readings with instructions or details found only in an SOP or repair report. The copilot collects record and document IDs for citations. Its grounding check can flag numbers that are absent from tool results, but it cannot guarantee that the model chose the right tool or interpreted the evidence correctly.

### Corrections, edits and conflicts

A user can correct a supported reading in chat, such as an inspection temperature or estimated spill volume. The system keeps the original value and records the corrected value, author, reason and timestamp. Calculations use the active correction. A later edit to that field in the database supersedes the chat correction.

Use **Edit data** to find an imported record and change its fields. Each edit requires a reason. The system saves the prior and new versions, who made the change, when it was made and why. A version check prevents an edit from silently overwriting a record that changed after it was opened. **History** shows the audit trail.

Statements saved as notes are marked unconfirmed. When a note, an inspection or a correction disagree, the copilot should show the conflicting evidence instead of treating a note as a verified reading.

### Conversations and memory

Conversation transcripts and answer details are saved in the selected SQL database for each user and chat. After a page reload, the app restores the saved chats and the active conversation history. The copilot also keeps a short, private list of recent topics for each user and selected database so a new chat can refer to earlier questions. That cross-chat topic list is currently held in backend memory, so it does not survive a backend restart; the saved transcript does.

Select a conversation in the sidebar to reopen it. Use the **×** beside a conversation to delete its transcript and saved context.

## Run it locally

### Requirements

- Java 21
- Maven
- [Ollama](https://ollama.com/) running locally
- Enough memory for the model you choose. The default is `gemma4:e4b`; smaller models need less memory but may be less reliable on multi-step questions.

### 1. Download the model

In PowerShell, download the default model:

```powershell
ollama pull gemma4:e4b
```

Make sure the Ollama service is running. Its default address is `http://localhost:11434`.

### 2. Start the app

From the repository folder, run:

```powershell
cd data/backend
mvn spring-boot:run
```

Open [http://localhost:8080](http://localhost:8080). The app creates and selects the sample database automatically on its first run.

To build a runnable JAR instead:

```powershell
cd data/backend
mvn -DskipTests package
java -jar target/asset-copilot-0.1.0.jar
```

### Optional configuration

Set these environment variables before starting the backend. In PowerShell, for example:

```powershell
$env:COPILOT_MODEL = "gemma4:e4b"
$env:COPILOT_NUM_CTX = "16384"
$env:OLLAMA_URL = "http://localhost:11434"
$env:COPILOT_DB_ROOT = ".\databases"
mvn spring-boot:run
```

| Variable | Default | Purpose |
| --- | --- | --- |
| `COPILOT_MODEL` | `gemma4:e4b` | Ollama model name |
| `COPILOT_NUM_CTX` | `16384` | Context length sent to Ollama |
| `OLLAMA_URL` | `http://localhost:11434` | Ollama server address |
| `COPILOT_DATA_ROOT` | `../../data/sample-data` | Initial sample dataset path |
| `COPILOT_DB_ROOT` | `./databases` | Folder for imported SQL databases |
| `COPILOT_AS_OF` | `2026-10-17` | Date used for questions such as “right now” and relative periods |

The default database folder is `data/backend/databases/`. It is ignored by Git, so imported operational data is not added to commits by default.

## Use the app

1. Choose your user in the top-right corner.
2. Choose the active database, or select **Import ZIP** and upload a dataset.
3. Ask a question in the chat. Name the well, area, time window or output format when relevant.
4. Review the answer, visual and source citations. Open **Reasoning steps** to see which tools were called.
5. Use **Edit data** to make a versioned change, or **History** to review prior changes.

Example questions:

- “Is WELL-0001 producing oil right now? Show its status card.”
- “Which spills in Southwest Block 24 missed the containment deadline in the last 90 days?”
- “Show a heatmap of 2026 spills by area and month.”
- “Map the pressure readings across the field and label what type of pressure each reading represents.”
- “Which well produced the most oil this year?” If production totals are missing, the assistant should say so and clearly label any related inspection-based operating-status information.

## Data layout

The sample dataset contains fleet information under `data/sample-data/fleet/` and well records under `data/sample-data/assets/`. Manuals and SOPs are in Markdown files; inspections, maintenance, notes and regulator events are stored as JSON or JSONL. See [02-dataset-guide.md](02-dataset-guide.md) for the sample fields and relationships, and [03-evaluation-queries.md](03-evaluation-queries.md) for example questions and expected results.

## Development notes

The backend is Java 21 with Spring Boot, JDBC and H2. The browser UI is static HTML, CSS and JavaScript served by Spring Boot. Ollama runs as a separate local service. The configured model is Gemma 4 E4B with a 16,384-token context. The app does not call a hosted model API by default; local hardware, electricity and storage are the main runtime costs.

## License

This project is available under the [MIT License](LICENSE).
