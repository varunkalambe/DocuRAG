// Paste your Render URL here. It must end with /api.
const PRODUCTION_API_URL = 'https://pdf-rag-backend-5coa.onrender.com/api';

const isLocalHost =
  typeof window !== 'undefined' &&
  ['localhost', '127.0.0.1'].includes(window.location.hostname) &&
  window.location.port === '4200';

export const environment = {
  production: !isLocalHost,
  apiBaseUrl: isLocalHost ? 'http://127.0.0.1:8000/api' : PRODUCTION_API_URL,
  maxUploadBytes: 10 * 1024 * 1024,
  maxQuestionLength: 10000,
  requestTimeoutMs: 90000,
  chatMockMode: false,
  mockResponseDelayMs: 700,
};
