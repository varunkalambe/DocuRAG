// After deploying the backend, paste its public URL here. It must end with /api.
// Example: https://pdf-rag-backend.onrender.com/api
const PRODUCTION_API_URL = 'https://YOUR-BACKEND-NAME.onrender.com/api';

// Local development (ng serve on localhost/127.0.0.1:4200) talks to FastAPI on port 8000.
// Any other host (the deployed Vercel build) talks to PRODUCTION_API_URL.
const isLocalHost =
  typeof window !== 'undefined' &&
  ['localhost', '127.0.0.1'].includes(window.location.hostname) &&
  window.location.port === '4200';

export const environment = {
  production: !isLocalHost,
  apiBaseUrl: isLocalHost ? 'http://127.0.0.1:8000/api' : PRODUCTION_API_URL,
  maxUploadBytes: 10 * 1024 * 1024,
  maxQuestionLength: 10000,
  // Raised from 60s so a cold-starting free-tier backend can wake up.
  requestTimeoutMs: 90000,
  chatMockMode: false,
  mockResponseDelayMs: 700,
};
