import { Injectable, computed, signal } from '@angular/core';

import { IndexedDocument } from './rag-api.service';

export type RequestPhase =
  | 'idle'
  | 'validating'
  | 'uploading'
  | 'processing'
  | 'retrieving'
  | 'generating'
  | 'completed'
  | 'failed';

export type MessageStatus = 'completed' | 'generating' | 'error';

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

export interface ConversationMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: string;
  status: MessageStatus;
  sources: SourceMetadata[];
}

export interface DocumentState {
  filename: string | null;
  sizeBytes: number;
  pageCount: number | null;
  chunkCount: number | null;
  indexedCount: number | null;
  status: string | null;
  error: string | null;
}

export interface ConversationState {
  messages: ConversationMessage[];
  sources: SourceMetadata[];
}

export interface RequestState {
  phase: RequestPhase;
  uploadProgress: number;
  message: string;
  error: string | null;
}

export interface ApplicationState {
  backendStatus: 'unknown' | 'checking' | 'healthy' | 'unavailable';
  documentAvailable: boolean;
  memoryResetState: 'idle' | 'clearing' | 'cleared' | 'failed';
}

const emptyDocument = (): DocumentState => ({
  filename: null,
  sizeBytes: 0,
  pageCount: null,
  chunkCount: null,
  indexedCount: null,
  status: null,
  error: null,
});

const emptyConversation = (): ConversationState => ({
  messages: [],
  sources: [],
});

const idleRequest = (): RequestState => ({
  phase: 'idle',
  uploadProgress: 0,
  message: 'Choose a PDF to begin.',
  error: null,
});

@Injectable({ providedIn: 'root' })
export class AppStateService {
  readonly document = signal<DocumentState>(emptyDocument());

  readonly conversation = signal<ConversationState>(emptyConversation());

  readonly request = signal<RequestState>(idleRequest());

  readonly application = signal<ApplicationState>({
    backendStatus: 'unknown',
    documentAvailable: false,
    memoryResetState: 'idle',
  });

  /** Documents held in the backend's persistent semantic memory. */
  readonly indexedDocuments = signal<IndexedDocument[]>([]);

  readonly indexedSummary = computed(() => {
    const documents = this.indexedDocuments();

    return {
      count: documents.length,
      chunks: documents.reduce((total, item) => total + item.chunk_count, 0),
      pages: documents.reduce((total, item) => total + item.page_count, 0),
      label: documents.length === 0
        ? 'None indexed'
        : documents.length === 1
          ? documents[0].filename
          : `${documents.length} documents`,
    };
  });

  readonly resetNonce = signal(0);

  readonly isBusy = computed(() => {
    const phase = this.request().phase;
    return phase === 'validating'
      || phase === 'uploading'
      || phase === 'processing'
      || phase === 'retrieving'
      || phase === 'generating';
  });

  setRequest(state: Partial<RequestState>): void {
    this.request.update((current) => ({ ...current, ...state }));
  }

  setDocument(state: Partial<DocumentState>): void {
    this.document.update((current) => ({ ...current, ...state }));
  }

  setApplication(state: Partial<ApplicationState>): void {
    this.application.update((current) => ({ ...current, ...state }));
  }

  addMessage(message: ConversationMessage): void {
    this.conversation.update((current) => ({
      ...current,
      messages: [...current.messages, message],
    }));
  }

  updateMessage(
    messageId: string,
    patch: Partial<ConversationMessage>,
  ): void {
    this.conversation.update((current) => ({
      ...current,
      messages: current.messages.map((message) =>
        message.id === messageId
          ? { ...message, ...patch }
          : message,
      ),
    }));
  }

  setConversationSources(sources: SourceMetadata[]): void {
    this.conversation.update((current) => ({
      ...current,
      sources,
    }));
  }

  setIndexedDocuments(documents: IndexedDocument[]): void {
    this.indexedDocuments.set(documents);
    this.application.update((current) => ({
      ...current,
      documentAvailable: documents.length > 0,
    }));
  }

  /**
   * Clears only the locally selected file. Documents already stored in the
   * backend's persistent memory and the conversation about them are kept.
   */
  resetSelectedDocument(): void {
    this.document.set(emptyDocument());
    this.request.set(idleRequest());

    this.application.update((current) => ({
      ...current,
      memoryResetState: 'idle',
    }));
  }

  resetConversation(): void {
    this.conversation.set(emptyConversation());
  }

  resetAfterMemoryClear(): void {
    this.indexedDocuments.set([]);
    this.document.set(emptyDocument());
    this.conversation.set(emptyConversation());
    this.request.set({
      phase: 'idle',
      uploadProgress: 0,
      message: 'Semantic memory is empty. Upload a new PDF to begin.',
      error: null,
    });
    this.application.update((current) => ({
      ...current,
      documentAvailable: false,
      memoryResetState: 'cleared',
    }));
    this.resetNonce.update((value) => value + 1);
  }
}
