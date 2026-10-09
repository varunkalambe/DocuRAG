# Deploy guide: Spring Boot backend (Render) + Angular frontend (Vercel)

Answer first: **do NOT create a new repo.** Keep the same GitHub repo (it holds `frontend/` and `backend/`).
You replace the contents of `backend/` (FastAPI -> Spring Boot), push, create ONE new Render service, update ONE line in the frontend, push again.

Why a new Render service: your old one is a Python service. Render fixes the runtime when a service is created, so make a new Docker service and delete the old one afterwards.

---------------------------------------------------------------------

## 0. Read this before you start (memory)

The backend runs the embedding model inside the app (ONNX). It needs about 0.5-1 GB RAM.
- Render **free** = 512 MB: may crash (logs show `Out of memory` or `exit 137`). Try it first, it costs nothing.
- If it crashes: Render Dashboard -> service -> Settings -> Instance Type -> **Standard (2 GB)**.
- Free instances sleep after ~15 min idle (first request after sleep takes 30-90 s) and their disk is wiped on every restart/redeploy, so uploaded PDFs are forgotten. Same as your old Chroma setup. A paid Render disk could fix it, but the container runs as a non-root user, so mounting one needs a small Dockerfile change: ask me before adding a disk.

---------------------------------------------------------------------

## 1. Put the code in your existing repo (Windows)

Open PowerShell:

```
cd "D:\Desktop Final\pdf-rag-application"
git status
git branch --show-current
git remote -v
git pull
```

1. Unzip `deploy-kit.zip` into `D:\Desktop Final\pdf-rag-application` and choose "Replace". It adds/overwrites:
   - `render.yaml`
   - `.gitignore`
   - `DEPLOY.md`
   - `frontend\src\environments\environment.ts`
2. Your `backend\` folder is already the Spring Boot one (you built it). Delete leftovers: `backend\.venv`, `backend\__pycache__`, old Python folders if any remain.
3. SAFETY CHECK, secrets must not be committed:
   ```
   git ls-files | findstr /i ".env"
   ```
   If it prints `backend/.env` (or any `.env`), remove it from git and rotate those keys:
   ```
   git rm --cached backend/.env
   ```
4. Commit and push (replace `main` with the branch name printed above if different):
   ```
   git add -A
   git status
   git commit -m "Replace FastAPI backend with Spring Boot"
   git push origin main
   ```

### If you have no repo yet
1. Create it: https://github.com/new (name e.g. `pdf-rag-application`, no README).
2. ```
   cd "D:\Desktop Final\pdf-rag-application"
   git init
   git add -A
   git commit -m "PDF RAG: Angular + Spring Boot"
   git branch -M main
   git remote add origin https://github.com/YOUR_USER/pdf-rag-application.git
   git push -u origin main
   ```

Note: when you push, your OLD Render service will try to redeploy and fail (it expects Python). That is harmless, the old version keeps running until you delete it in step 5.

---------------------------------------------------------------------

## 2. Create the Render service

Dashboard: https://dashboard.render.com

### Option A (recommended): Blueprint
1. **New +** -> **Blueprint** -> pick your GitHub repo (authorize Render if asked) -> branch `main`.
2. Render reads `render.yaml` and asks for the two secret values:
   - `GROQ_API_KEY` = your key from https://console.groq.com/keys
   - `CORS_ALLOWED_ORIGINS` = your Vercel URL, exactly, e.g. `https://your-app.vercel.app` (no trailing slash; several allowed, comma separated)
3. **Apply**. First build takes 5-10 min (Maven + Docker).

### Option B: manual
**New +** -> **Web Service** -> your repo ->
- Name: `pdf-rag-springboot`
- Language/Runtime: **Docker**
- Branch: `main`
- Root Directory: `backend`
- Dockerfile Path: `./Dockerfile`
- Instance type: Free (or Standard)
- Health Check Path: `/api/health`
- Environment variables:

| Key | Value |
|---|---|
| APP_ENV | production |
| GROQ_API_KEY | gsk_... (your key) |
| GROQ_MODEL | openai/gpt-oss-20b |
| EMBEDDING_PROVIDER | local |
| CORS_ALLOWED_ORIGINS | https://your-app.vercel.app |
| JAVA_TOOL_OPTIONS | -XX:MaxRAMPercentage=45 -XX:+UseSerialGC -Xss512k -XX:MaxMetaspaceSize=128m -XX:+ExitOnOutOfMemoryError |

Do not set `PORT`, Render sets it and the app reads it.

Optional env vars (defaults are fine): `MAX_UPLOAD_SIZE_BYTES`, `CHUNK_SIZE`, `TOP_K`, `RELEVANCE_THRESHOLD`, `GROQ_TEMPERATURE`, `GROQ_MAX_COMPLETION_TOKENS`.

### Verify the backend
Copy the service URL from the Render page (e.g. `https://pdf-rag-springboot.onrender.com`), then:
```
curl.exe https://pdf-rag-springboot.onrender.com/api/health
```
Expected: `{"success":true,"data":{"status":"healthy",...}}` (first call after sleep can take up to 90 s).
Logs: Render service -> **Logs**. Look for `application_started` and `local_embedding_model_loaded`.

---------------------------------------------------------------------

## 3. Point the Vercel frontend at the new backend

1. Open `frontend\src\environments\environment.ts`. Line 3 must contain YOUR exact Render URL ending in `/api`:
   ```ts
   const PRODUCTION_API_URL = 'https://pdf-rag-springboot.onrender.com/api';
   ```
   (Render adds a random suffix if the name was taken, so copy the URL it shows.)
2. Push:
   ```
   cd "D:\Desktop Final\pdf-rag-application"
   git add -A
   git commit -m "Point frontend to Spring Boot backend"
   git push origin main
   ```
3. Vercel (https://vercel.com/dashboard) redeploys automatically. Check the project's **Settings -> General**: Root Directory `frontend`, Framework Angular, Build Command `npm run build` (already how it works today, no change needed).
4. Open your Vercel URL, hard refresh (Ctrl+F5), upload a PDF, ask a question.

---------------------------------------------------------------------

## 4. CORS (the usual cause of "Failed to load resource" after deploy)

`CORS_ALLOWED_ORIGINS` on Render must equal the exact browser origin of the frontend:
- yes: `https://your-app.vercel.app`
- no: trailing `/`, `http://` instead of `https://`, or a different Vercel URL.

Custom domain + Vercel URL: `https://app.example.com,https://your-app.vercel.app`.
Vercel preview URLs (one per branch) are different origins. To allow them too, add env var on Render:
`CORS_ALLOW_ORIGIN_REGEX` = `https://your-app-.*\.vercel\.app`
After changing env vars Render restarts the service automatically.

---------------------------------------------------------------------

## 5. Remove the old FastAPI service

Only after the new site works end to end:
Render Dashboard -> old service `pdf-rag-backend-5coa` -> **Settings** -> scroll down -> **Delete Web Service**.

---------------------------------------------------------------------

## 6. Everyday commands

Local run (3 terminals):
```
# Terminal 1
cd "D:\Desktop Final\pdf-rag-application\backend"
mvn clean package -DskipTests
java -jar target\pdf-rag-backend.jar

# Terminal 2
cd "D:\Desktop Final\pdf-rag-application\frontend"
npm start

# Terminal 3 (smoke test)
curl.exe http://localhost:8000/api/health
```
Run tests: `cd backend` then `mvn test`.
Deploy a change: `git add -A`, `git commit -m "msg"`, `git push origin main` (Render and Vercel both auto-deploy).
Change a secret (e.g. new Groq key): Render -> service -> **Environment** -> edit `GROQ_API_KEY` -> Save (auto restart).

---------------------------------------------------------------------

## 7. Troubleshooting

| Symptom | Fix |
|---|---|
| Render logs `exit 137` / Out of memory | Instance Type -> Standard (2 GB) |
| Browser: CORS error | Fix `CORS_ALLOWED_ORIGINS` exactly (section 4) |
| First request very slow / 502 | Free instance was asleep, wait 60-90 s and retry |
| `GENERATION_PERMISSION_DENIED` (403) | Key blocked/restricted: create a new key at https://console.groq.com/keys |
| `GENERATION_RATE_LIMIT` (429) | Groq free limit reached: wait, or use another key (limits: https://console.groq.com/settings/limits) |
| Upload works then "no documents" after a while | Free disk is wiped on restart; re-upload (a persistent disk needs a small Dockerfile change, ask me) |
| Frontend still calls old backend | `environment.ts` URL wrong or Vercel not redeployed; hard refresh |
| Startup fails: "Missing mandatory configuration value" | Set `GROQ_API_KEY` and `GROQ_MODEL` in Render env |
| Docker build fails | Open the Render build log; paste the first `ERROR` line to me |

Low-memory alternative: `EMBEDDING_PROVIDER=huggingface` plus `HUGGINGFACE_API_TOKEN` (needs Hugging Face credits) and `EMBEDDING_FALLBACK_TO_LOCAL=false` keeps RAM low, but your credits were exhausted before.
