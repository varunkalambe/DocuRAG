import { Injectable, effect, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { timeout } from 'rxjs/operators';

import { environment } from '../../environments/environment';
import {
  AppStateService,
  ConversationMessage,
} from './app-state.service';
import { RagApiService } from './rag-api.service';

const STORAGE_KEY = 'pdf-rag.conversation.v1';

interface StoredConversation {
  signature: string;
  messages: ConversationMessage[];
}

/**
 * Keeps the browser in sync with the backend's persistent semantic memory:
 * - restores the list of indexed documents after a page refresh;
 * - restores the chat history for exactly that set of documents.
 */
@Injectable({ providedIn: 'root' })
export class MemorySyncService {
  private readonly state = inject(AppStateService);
  private readonly api = inject(RagApiService);

  private conversationRestored = false;

  private readonly persistEffect = effect(() => {
    const messages = this.state.conversation().messages;
    const signature = this.signature();

    if (!this.conversationRestored) {
      return;
    }

    this.save(signature, messages);
  });

  async refreshDocuments(): Promise<boolean> {
    try {
      const response = await firstValueFrom(
        this.api.listDocuments().pipe(
          timeout({ each: environment.requestTimeoutMs }),
        ),
      );

      if (!response?.success || !Array.isArray(response.data?.documents)) {
        return false;
      }

      this.state.setIndexedDocuments(response.data.documents);
      return true;
    } catch {
      return false;
    }
  }

  async restore(): Promise<void> {
    const loaded = await this.refreshDocuments();

    if (!loaded || this.conversationRestored) {
      return;
    }

    const stored = this.load();

    if (
      stored
      && this.state.indexedDocuments().length > 0
      && stored.signature === this.signature()
      && this.state.conversation().messages.length === 0
    ) {
      this.state.conversation.update((current) => ({
        ...current,
        messages: stored.messages,
      }));
    } else if (this.state.indexedDocuments().length === 0) {
      this.clearStorage();
    }

    this.conversationRestored = true;
  }

  private signature(): string {
    return this.state
      .indexedDocuments()
      .map((item) => item.document_id)
      .sort()
      .join('|');
  }

  private save(signature: string, messages: ConversationMessage[]): void {
    try {
      const persistable = messages.filter(
        (message) => message.status !== 'generating',
      );

      if (!signature || persistable.length === 0) {
        localStorage.removeItem(STORAGE_KEY);
        return;
      }

      const payload: StoredConversation = {
        signature,
        messages: persistable,
      };
      localStorage.setItem(STORAGE_KEY, JSON.stringify(payload));
    } catch {
      // Storage can be unavailable or full; persistence is best-effort.
    }
  }

  private load(): StoredConversation | null {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);

      if (!raw) {
        return null;
      }

      const parsed = JSON.parse(raw) as StoredConversation;

      if (
        typeof parsed?.signature !== 'string'
        || !Array.isArray(parsed.messages)
      ) {
        return null;
      }

      return parsed;
    } catch {
      return null;
    }
  }

  private clearStorage(): void {
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      // Best-effort only.
    }
  }
}
