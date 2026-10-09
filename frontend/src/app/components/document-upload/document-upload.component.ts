import {
  Component,
  DestroyRef,
  ElementRef,
  ViewChild,
  effect,
  inject,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  HttpEventType,
  HttpResponse,
} from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { timeout } from 'rxjs/operators';

import { AppStateService } from '../../services/app-state.service';
import {
  RagApiService,
  UploadResponse,
} from '../../services/rag-api.service';
import { PdfClientValidatorService } from '../../services/pdf-client-validator.service';
import { AppErrorService } from '../../services/app-error.service';
import { MemorySyncService } from '../../services/memory-sync.service';

@Component({
  selector: 'app-document-upload',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './document-upload.component.html',
  styleUrl: './document-upload.component.css',
})
export class DocumentUploadComponent {
  readonly state = inject(AppStateService);
  private readonly api = inject(RagApiService);
  private readonly validator = inject(PdfClientValidatorService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly errors = inject(AppErrorService);
  private readonly memorySync = inject(MemorySyncService);

  private readonly resetEffect = effect(() => {
    const nonce = this.state.resetNonce();
    if (nonce > 0) {
      this.clearLocalSelection();
    }
  });

  @ViewChild('fileInput')
  private fileInput?: ElementRef<HTMLInputElement>;

  isDragOver = false;

  get selectedFile(): File | null {
    return this._selectedFile;
  }

  onFileInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    // Clear the native input so choosing the same file again still fires (change).
    input.value = '';
    this.selectFile(file);
  }

  onDragEnter(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver = true;
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver = true;
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver = false;
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver = false;

    this.selectFile(event.dataTransfer?.files?.[0] ?? null);
  }

  openFilePicker(): void {
    if (!this.state.isBusy()) {
      this.fileInput?.nativeElement.click();
    }
  }

  removeFile(): void {
    if (this.state.isBusy()) {
      return;
    }

    this._selectedFile = null;
    this.state.resetSelectedDocument();

    if (this.fileInput) {
      this.fileInput.nativeElement.value = '';
    }
  }

  selectFile(file: File | null): void {
    this._selectedFile = null;
    this.state.resetSelectedDocument();

    if (!file) {
      return;
    }

    this.state.setRequest({
      phase: 'validating',
      uploadProgress: 0,
      message: 'Checking the selected PDF...',
      error: null,
    });

    const validation = this.validator.validate(file);

    if (!validation.valid) {
      this.state.setDocument({
        filename: file.name,
        sizeBytes: file.size,
        error: validation.message,
      });

      this.state.setRequest({
        phase: 'failed',
        message: 'This file cannot be uploaded.',
        error: validation.message ?? 'Invalid PDF.',
      });
      return;
    }

    this._selectedFile = file;

    this.state.setDocument({
      filename: file.name,
      sizeBytes: file.size,
      error: null,
    });

    this.state.setRequest({
      phase: 'idle',
      uploadProgress: 0,
      message: 'PDF is ready to index.',
      error: null,
    });
  }

  upload(): void {
    const file = this._selectedFile;

    if (!file) {
      this.state.setRequest({
        phase: 'failed',
        message: 'Choose a PDF before indexing.',
        error: 'No PDF is selected.',
      });
      return;
    }

    if (this.state.isBusy()) {
      return;
    }

    this.state.setRequest({
      phase: 'validating',
      uploadProgress: 0,
      message: 'Validating the selected PDF...',
      error: null,
    });

    const validation = this.validator.validate(file);

    if (!validation.valid) {
      this.state.setDocument({ error: validation.message });
      this.state.setRequest({
        phase: 'failed',
        message: 'Client-side validation failed.',
        error: validation.message ?? 'Invalid PDF.',
      });
      return;
    }

    this.state.setRequest({
      phase: 'uploading',
      uploadProgress: 0,
      message: 'Uploading PDF to Spring Boot...',
      error: null,
    });

    this.api
      .uploadDocument(file)
      .pipe(
        timeout({ each: 300000 }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (event) => {
          if (event.type === HttpEventType.UploadProgress) {
            const loaded = event.loaded;
            const total = event.total ?? file.size;
            const progress = total > 0
              ? Math.round((loaded / total) * 100)
              : 0;

            this.state.setRequest({
              phase: progress >= 100 ? 'processing' : 'uploading',
              uploadProgress: progress,
              message: progress >= 100
                ? 'Upload complete. Spring Boot is indexing the document...'
                : `Uploading PDF... ${progress}%`,
            });
            return;
          }

          if (event.type === HttpEventType.Sent) {
            this.state.setRequest({
              phase: 'uploading',
              message: 'Upload request sent. Waiting for Spring Boot...',
            });
            return;
          }

          if (event instanceof HttpResponse) {
            const response = event.body as UploadResponse | null;

            if (!response?.success || !response.data) {
              this.fail('The backend returned an invalid upload response.');
              return;
            }

            this.state.setRequest({
              phase: 'processing',
              uploadProgress: 100,
              message: 'Indexing: extraction → chunking → embeddings → vector index...',
              error: null,
            });

            this.state.setDocument({
              filename: response.data.filename,
              pageCount: response.data.page_count,
              chunkCount: response.data.chunk_count,
              indexedCount: response.data.indexed_count,
              status: response.data.status,
              error: null,
            });

            this.state.setApplication({
              documentAvailable: response.data.indexed_count > 0,
            });

            this.state.setRequest({
              phase: 'completed',
              uploadProgress: 100,
              message: response.data.message,
              error: null,
            });

            void this.memorySync.refreshDocuments();
          }
        },
        error: (error: unknown) => {
          const userError = this.errors.fromHttpError(error);
          this.fail(userError.message);
        },
      });
  }

  clearLocalSelection(): void {
    this._selectedFile = null;

    if (this.fileInput) {
      this.fileInput.nativeElement.value = '';
    }
  }

  private _selectedFile: File | null = null;

  private fail(message: string): void {
    this.state.setDocument({
      error: message,
    });

    this.state.setRequest({
      phase: 'failed',
      message: 'Document indexing failed. You can retry.',
      error: message,
    });
  }
}
