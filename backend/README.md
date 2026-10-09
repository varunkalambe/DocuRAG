# PDF RAG backend (Spring Boot 3 / Java 21)

Drop-in replacement for the FastAPI backend. Same port (8000), same `/api/*` routes, same JSON contract, same env var names.
Frontend: **no changes needed.**

## Replace old backend
```
# project root (folder containing frontend/ and backend/)
# 1. delete old backend/ (python code, venv, chroma_data)
# 2. unzip this archive here -> new backend/
```

## Prerequisites
- JDK 21 (`java -version`)
- Maven 3.9+ (`mvn -v`)

## Configure
```
cd backend
cp .env.example .env          # Windows: copy .env.example .env
# edit .env: GROQ_API_KEY, GROQ_MODEL ; keep EMBEDDING_PROVIDER=local
```
No quotes around values in `.env`.

## Terminal 1 - backend
macOS / Linux:
```
cd backend
mvn clean package
java -jar target/pdf-rag-backend.jar
```
Windows PowerShell / CMD:
```
cd backend
mvn clean package
java -jar target\pdf-rag-backend.jar
```
Dev mode: `mvn spring-boot:run`

## Terminal 2 - frontend (unchanged)
```
cd frontend
npm install
npm start
```
Open http://localhost:4200

## Terminal 3 - smoke test
macOS / Linux / Git Bash:
```
curl -s http://localhost:8000/api/health
curl -s -F "file=@sample.pdf;type=application/pdf" http://localhost:8000/api/documents/upload
curl -s http://localhost:8000/api/documents
curl -s -X POST http://localhost:8000/api/query -H "Content-Type: application/json" -d '{"question":"What is this document about?"}'
curl -s -X DELETE http://localhost:8000/api/memory
```
Windows PowerShell (use `curl.exe`):
```
curl.exe -s http://localhost:8000/api/health
curl.exe -s -F "file=@sample.pdf;type=application/pdf" http://localhost:8000/api/documents/upload
curl.exe -s -X POST http://localhost:8000/api/query -H "Content-Type: application/json" -d "{\"question\":\"What is this document about?\"}"
```

## Automated tests
```
cd backend
mvn test
```
Covers classifier, chunker, vector store, and full HTTP flow (upload, duplicate, query, document overview, validation errors, CORS, clear memory) with fake embedder/LLM (no network).

## Production
Docker:
```
cd backend
docker build -t pdf-rag-backend .
docker run -d --name pdf-rag -p 8000:8000 --env-file .env -v pdfrag-data:/data pdf-rag-backend
docker logs -f pdf-rag
curl http://localhost:8000/actuator/health
```
Set in production env: `APP_ENV=production`, `GROQ_API_KEY`, `GROQ_MODEL`, `CORS_ALLOWED_ORIGINS=https://your-frontend`, `CHROMA_PERSIST_DIR` on a persistent volume. Platforms that set `PORT` (Render, Railway) work automatically. Min ~1 GB RAM (ONNX model in-process). Use Debian/Ubuntu base images, not Alpine.

Health: `/api/health` (app), `/actuator/health/liveness`, `/actuator/health/readiness`.
Frontend `environment.prod.ts` must point `apiBaseUrl` to the deployed backend URL + `/api`.

## Embeddings (fixes the 402 error)
- `EMBEDDING_PROVIDER=local` (default): in-process all-MiniLM-L6-v2 (384-dim), no key, no credits.
- `EMBEDDING_PROVIDER=huggingface`: remote; with `EMBEDDING_FALLBACK_TO_LOCAL=true` a 402/429/5xx falls back to local automatically.
- Switching provider/model changes vectors: press **Clear memory** and re-upload.

## Vector storage
ChromaDB has no Java embedded engine, so vectors are stored in `CHROMA_PERSIST_DIR/vectors.bin` (atomic writes, loaded at startup, exact cosine search). Old Chroma data is not read: re-upload documents.
