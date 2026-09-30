import { Component, DestroyRef, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { timeout } from 'rxjs/operators';

import { environment } from '../../../environments/environment';
import { AppErrorService } from '../../services/app-error.service';
import { AppStateService } from '../../services/app-state.service';
import { RagApiService } from '../../services/rag-api.service';

@Component({
  selector: 'app-memory-controls',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './memory-controls.component.html',
  styleUrl: './memory-controls.component.css',
})
export class MemoryControlsComponent {
  readonly state = inject(AppStateService);
  private readonly api = inject(RagApiService);
  private readonly errors = inject(AppErrorService);
  private readonly destroyRef = inject(DestroyRef);

  reset(): void {
    if (this.state.isBusy() || this.state.application().memoryResetState === 'clearing') {
      return;
    }

    const confirmed = window.confirm(
      'Wipe Memory will delete the current semantic document memory from the FastAPI Chroma store and reset the Angular document/conversation state. Continue?',
    );

    if (!confirmed) {
      return;
    }

    this.state.setApplication({ memoryResetState: 'clearing' });
    this.state.setRequest({
      phase: 'processing',
      message: 'Clearing semantic memory from the backend...',
      error: null,
    });

    this.api
      .resetMemory()
      .pipe(
        timeout({ each: environment.requestTimeoutMs }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => {
          this.state.resetAfterMemoryClear();
        },
        error: (error: unknown) => {
          const userError = this.errors.fromHttpError(error);
          this.state.setApplication({ memoryResetState: 'failed' });
          this.state.setRequest({
            phase: 'failed',
            message: 'Memory reset failed.',
            error: userError.message,
          });
        },
      });
  }
}
