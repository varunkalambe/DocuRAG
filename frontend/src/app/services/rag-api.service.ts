import { Injectable, inject } from '@angular/core';
import {
  HttpClient,
  HttpEvent,
  HttpRequest,
} from '@angular/common/http';
import { Observable } from 'rxjs';

import { environment } from '../../environments/environment';

export interface HealthResponse {
  success: boolean;
  data: {
    status: string;
    application: string;
    environment: string;
  };
}

export interface UploadResponse {
  success: boolean;
  data: {
    document_id: string;
    filename: string;
    status: string;
    message: string;
    page_count: number;
    empty_page_count: number;
    chunk_count: number;
    indexed_count: number;
  };
}

export interface ErrorResponse {
  success: false;
  error: {
    code: string;
    message: string;
    details?: unknown;
  };
}

export interface SourceMetadata {
  source_id: string;
  filename: string;
  chunk_id: string;
  start_page: number;
  end_page: number;
  sequence: number;
  rank: number;
  distance: number;
}

export interface RetrievalMetadata {
  candidates: number;
  accepted: number;
  top_k: number;
  relevance_threshold: number;
  context_token_estimate: number;
}

export interface QueryData {
  answer: string;
  sources: SourceMetadata[];
  status: string;
  retrieval: RetrievalMetadata;
}

export interface QueryResponse {
  success: boolean;
  data: QueryData;
}

export interface IndexedDocument {
  document_id: string;
  filename: string;
  chunk_count: number;
  page_count: number;
}

export interface DocumentListResponse {
  success: boolean;
  data: {
    documents: IndexedDocument[];
    total_chunks: number;
  };
}

export interface MemoryResetResponse {
  success: boolean;
  data: {
    status: string;
    message: string;
  };
}

@Injectable({ providedIn: 'root' })
export class RagApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = environment.apiBaseUrl;

  health(): Observable<HealthResponse> {
    return this.http.get<HealthResponse>(`${this.baseUrl}/health`);
  }

  uploadDocument(file: File): Observable<HttpEvent<UploadResponse>> {
    const formData = new FormData();
    formData.append('file', file, file.name);

    const request = new HttpRequest<FormData>(
      'POST',
      `${this.baseUrl}/documents/upload`,
      formData,
      { reportProgress: true },
    );

    return this.http.request<UploadResponse>(request);
    }

  listDocuments(): Observable<DocumentListResponse> {
    return this.http.get<DocumentListResponse>(`${this.baseUrl}/documents`);
  }

  query(question: string): Observable<QueryResponse> {
    return this.http.post<QueryResponse>(
      `${this.baseUrl}/query`,
      { question },
    );
  }

  resetMemory(): Observable<MemoryResetResponse> {
    return this.http.delete<MemoryResetResponse>(`${this.baseUrl}/memory`);
  }
}
