import { Injectable } from '@angular/core';

export type AppErrorCategory =
  | 'file-validation'
  | 'document-extraction'
  | 'document-ingestion'
  | 'retrieval-unavailable'
  | 'generation-unavailable'
  | 'rate-limited'
  | 'backend-unavailable'
  | 'unknown';

export interface UserFacingError {
  category: AppErrorCategory;
  code: string;
  message: string;
  retryable: boolean;
}

@Injectable({ providedIn: 'root' })
export class AppErrorService {
  fromHttpError(error: any): UserFacingError {
    const code = String(
      error?.error?.error?.code
      ?? error?.error?.code
      ?? 'NETWORK_ERROR',
    );

    const serverMessage = String(
      error?.error?.error?.message
      ?? error?.error?.message
      ?? error?.message
      ?? '',
    ).trim();

    if (error?.name === 'TimeoutError' || error?.status === 0) {
      return {
        category: 'backend-unavailable',
        code,
        message: 'The backend did not respond. Check that FastAPI is running and try again.',
        retryable: true,
      };
    }

    if (code === 'FILE_TOO_LARGE'
      || code === 'INVALID_FILENAME'
      || code === 'INVALID_CONTENT_TYPE'
      || code === 'INVALID_PDF_SIGNATURE'
      || code === 'EMPTY_FILE') {
      return {
        category: 'file-validation',
        code,
        message: serverMessage || 'The selected PDF is not valid for this application.',
        retryable: false,
      };
    }

    if (code === 'PAGE_EXTRACTION_FAILED'
      || code === 'PDF_EXTRACTION_FAILED'
      || code === 'NO_EXTRACTABLE_TEXT'
      || code === 'PDF_ENCRYPTED'
      || code === 'CORRUPTED_PDF') {
      return {
        category: 'document-extraction',
        code,
        message: serverMessage || 'The PDF could not be converted into usable text.',
        retryable: false,
      };
    }

    if (code === 'GENERATION_RATE_LIMIT'
      || code === 'EMBEDDING_RATE_LIMIT') {
      return {
        category: 'rate-limited',
        code,
        message: serverMessage || 'The AI provider rate limit was reached. Try again shortly.',
        retryable: true,
      };
    }

    if (code.startsWith('EMBEDDING_') || code === 'NO_CHUNKS_CREATED' || code === 'INDEXING_FAILED' || code.startsWith('INDEX_')) {
      return {
        category: 'document-ingestion',
        code,
        message: serverMessage || 'The document could not be indexed successfully.',
        retryable: true,
      };
    }

    if (code === 'NO_DOCUMENTS_INDEXED'
      || code.startsWith('RETRIEVAL_')
      || code === 'INVALID_RETRIEVAL_DISTANCE') {
      return {
        category: 'retrieval-unavailable',
        code,
        message: serverMessage || 'Document retrieval is currently unavailable.',
        retryable: true,
      };
    }

    if (code.startsWith('GENERATION_')) {
      return {
        category: 'generation-unavailable',
        code,
        message: serverMessage || 'Answer generation is currently unavailable.',
        retryable: true,
      };
    }

    return {
      category: 'unknown',
      code,
      message: serverMessage || 'The request failed. Please try again.',
      retryable: true,
    };
  }
}
