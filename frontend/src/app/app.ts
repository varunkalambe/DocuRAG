import {
  Component,
  DestroyRef,
  OnInit,
  inject,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { timeout } from 'rxjs/operators';

import { environment } from '../environments/environment';
import { AppErrorService } from './services/app-error.service';
import { AppStateService } from './services/app-state.service';
import { MemorySyncService } from './services/memory-sync.service';
import { RagApiService } from './services/rag-api.service';
import { ChatComponent } from './components/chat/chat.component';
import { DocumentUploadComponent } from './components/document-upload/document-upload.component';
import { MemoryControlsComponent } from './components/memory-controls/memory-controls.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    ChatComponent,
    DocumentUploadComponent,
    MemoryControlsComponent,
  ],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit {
  readonly state = inject(AppStateService);
  private readonly api = inject(RagApiService);
  private readonly errors = inject(AppErrorService);
  private readonly memorySync = inject(MemorySyncService);
  private readonly destroyRef = inject(DestroyRef);

  readonly title = 'PDF RAG Application';
  readonly environment = environment;

  ngOnInit(): void {
    this.checkBackend();
  }

  refreshBackendStatus(): void {
    this.checkBackend();
  }

  private checkBackend(): void {
    this.state.setApplication({ backendStatus: 'checking' });

    this.api
      .health()
      .pipe(
        timeout({ each: environment.requestTimeoutMs }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => {
          this.state.setApplication({ backendStatus: 'healthy' });
          void this.memorySync.restore();

          const request = this.state.request();
          if (request.phase === 'failed' && request.message === 'Backend is unavailable.') {
            this.state.setRequest({
              phase: 'idle',
              message: 'Choose a PDF to begin.',
              error: null,
            });
          }
        },
        error: (error: unknown) => {
          const userError = this.errors.fromHttpError(error);
          this.state.setApplication({ backendStatus: 'unavailable' });
          this.state.setRequest({
            phase: 'failed',
            message: 'Backend is unavailable.',
            error: userError.message,
          });
        },
      });
  }
}
