import {
  AfterViewInit,
  Component,
  DestroyRef,
  ElementRef,
  ViewChild,
  effect,
  inject,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { timeout } from 'rxjs/operators';

import { environment } from '../../../environments/environment';
import {
  AppStateService,
  ConversationMessage,
  SourceMetadata,
} from '../../services/app-state.service';
import { AppErrorService } from '../../services/app-error.service';
import { ChatService } from '../../services/chat.service';
import { SourceListComponent } from '../source-list/source-list.component';

@Component({
  selector: 'app-chat',
  standalone: true,
  imports: [CommonModule, SourceListComponent],
  templateUrl: './chat.component.html',
  styleUrl: './chat.component.css',
})
export class ChatComponent implements AfterViewInit {
  readonly state = inject(AppStateService);
  private readonly chat = inject(ChatService);
  private readonly errors = inject(AppErrorService);
  private readonly destroyRef = inject(DestroyRef);

  @ViewChild('messageViewport')
  private messageViewport?: ElementRef<HTMLDivElement>;

  question = '';
  validationMessage = '';

  private readonly scrollEffect = effect(() => {
    this.state.conversation().messages.length;
    this.state.request().phase;
    queueMicrotask(() => this.scrollToBottom());
  });

  ngAfterViewInit(): void {
    this.scrollToBottom();
  }

  readonly environmentMaxQuestionLength = environment.maxQuestionLength;

  get remainingCharacters(): number {
    return environment.maxQuestionLength - this.question.length;
  }

  get canSubmit(): boolean {
    return this.state.application().documentAvailable
      && !this.state.isBusy()
      && this.question.trim().length > 0
      && this.question.trim().length <= environment.maxQuestionLength;
  }

  submit(): void {
    this.validationMessage = '';

    if (this.state.isBusy()) {
      return;
    }

    if (!this.state.application().documentAvailable) {
      this.validationMessage = 'Upload and index a PDF before asking a question.';
      return;
    }

    const normalizedQuestion = this.question.trim();

    if (!normalizedQuestion) {
      this.validationMessage = 'Question cannot be empty.';
      return;
    }

    if (normalizedQuestion.length > environment.maxQuestionLength) {
      this.validationMessage = `Question cannot exceed ${environment.maxQuestionLength.toLocaleString()} characters.`;
      return;
    }

    const userId = this.createMessageId('user');
    const assistantId = this.createMessageId('assistant');

    const userMessage: ConversationMessage = {
      id: userId,
      role: 'user',
      content: normalizedQuestion,
      timestamp: new Date().toISOString(),
      status: 'completed',
      sources: [],
    };

    const assistantMessage: ConversationMessage = {
      id: assistantId,
      role: 'assistant',
      content: 'Generating…',
      timestamp: new Date().toISOString(),
      status: 'generating',
      sources: [],
    };

    this.state.addMessage(userMessage);
    this.state.addMessage(assistantMessage);
    this.state.setConversationSources([]);
    this.state.setRequest({
      phase: 'retrieving',
      message: 'Retrieving relevant evidence from the indexed document...',
      error: null,
    });
    this.question = '';

    this.chat
      .send(normalizedQuestion)
      .pipe(
        timeout({ each: environment.requestTimeoutMs }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (result) => {
          const sources: SourceMetadata[] = result.sources.map((source) => ({
            source_id: source.source_id,
            filename: source.filename,
            chunk_id: source.chunk_id,
            start_page: source.start_page,
            end_page: source.end_page,
            sequence: source.sequence,
            rank: source.rank,
            distance: source.distance,
          }));

          this.state.updateMessage(assistantId, {
            content: result.answer,
            timestamp: new Date().toISOString(),
            status: 'completed',
            sources,
          });
          this.state.setConversationSources(sources);
          this.state.setRequest({
            phase: 'completed',
            message: result.status === 'abstained'
              ? 'No sufficient evidence was found; the system abstained.'
              : 'Answer completed.',
            error: null,
          });
        },
        error: (error: unknown) => {
          const userError = this.errors.fromHttpError(error);
          this.state.updateMessage(assistantId, {
            content: userError.message,
            timestamp: new Date().toISOString(),
            status: 'error',
            sources: [],
          });
          this.state.setConversationSources([]);
          this.state.setRequest({
            phase: 'failed',
            message: 'Question request failed.',
            error: userError.message,
          });
        },
      });
  }

  handleInput(event: Event): void {
    const target = event.target as HTMLTextAreaElement;
    this.question = target.value;

    if (this.validationMessage) {
      this.validationMessage = '';
    }

    if (this.question.length > environment.maxQuestionLength) {
      this.validationMessage = `Maximum length is ${environment.maxQuestionLength.toLocaleString()} characters.`;
    }
  }

  private createMessageId(prefix: string): string {
    return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
  }

  private scrollToBottom(): void {
    if (!this.messageViewport) {
      return;
    }

    const element = this.messageViewport.nativeElement;
    element.scrollTo({
      top: element.scrollHeight,
      behavior: 'smooth',
    });
  }
}
