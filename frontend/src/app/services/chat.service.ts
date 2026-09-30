import { Injectable, inject } from '@angular/core';
import { Observable, of } from 'rxjs';
import { delay, map, timeout } from 'rxjs/operators';

import { environment } from '../../environments/environment';
import {
  QueryData,
  RagApiService,
} from './rag-api.service';

@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly api = inject(RagApiService);

  send(question: string): Observable<QueryData> {
    if (environment.chatMockMode) {
      return this.mockAnswer(question).pipe(
        delay(environment.mockResponseDelayMs),
      );
    }

    return this.api.query(question).pipe(
      timeout({ each: environment.requestTimeoutMs }),
      map((response) => {
        if (!response || response.success !== true || !response.data) {
          throw new Error('The backend returned a malformed query response.');
        }

        if (typeof response.data.answer !== 'string'
          || !Array.isArray(response.data.sources)
          || typeof response.data.status !== 'string'
          || !response.data.retrieval) {
          throw new Error('The backend query response did not match the expected contract.');
        }

        return response.data;
      }),
    );
  }

  private mockAnswer(question: string): Observable<QueryData> {
    return of({
      answer:
        `Mock mode is active. You asked: "${question}". `
        + 'This simulated response exists so the Angular chat can be verified independently of the RAG backend.',
      sources: [],
      status: 'answered',
      retrieval: {
        candidates: 0,
        accepted: 0,
        top_k: 0,
        relevance_threshold: 0,
        context_token_estimate: 0,
      },
    });
  }
}
