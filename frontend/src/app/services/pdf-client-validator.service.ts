import { Injectable } from '@angular/core';

import { environment } from '../../environments/environment';

export interface PdfValidationResult {
  valid: boolean;
  code: string | null;
  message: string | null;
}

@Injectable({ providedIn: 'root' })
export class PdfClientValidatorService {
  validate(file: File | null): PdfValidationResult {
    if (!file) {
      return {
        valid: false,
        code: 'MISSING_FILE',
        message: 'Please select a PDF file.',
      };
    }

    if (file.size <= 0) {
      return {
        valid: false,
        code: 'EMPTY_FILE',
        message: 'The selected file is empty.',
      };
    }

    if (file.size > environment.maxUploadBytes) {
      return {
        valid: false,
        code: 'FILE_TOO_LARGE',
        message: `The PDF exceeds the ${this.formatBytes(environment.maxUploadBytes)} limit.`,
      };
    }

    const isPdfExtension = file.name
      .toLowerCase()
      .endsWith('.pdf');

    if (!isPdfExtension) {
      return {
        valid: false,
        code: 'INVALID_FILENAME',
        message: 'Only .pdf files are accepted.',
      };
    }

    // Browsers/OSes label PDFs inconsistently; the backend verifies the real
    // PDF signature, so common aliases are accepted here.
    const acceptedMimeTypes = [
      'application/pdf',
      'application/x-pdf',
      'application/acrobat',
      'application/octet-stream',
    ];

    if (
      file.type &&
      !acceptedMimeTypes.includes(file.type.toLowerCase())
    ) {
      return {
        valid: false,
        code: 'INVALID_CONTENT_TYPE',
        message: 'The browser reports a non-PDF MIME type.',
      };
    }

    return {
      valid: true,
      code: null,
      message: null,
    };
  }

  formatBytes(bytes: number): string {
    const mb = bytes / (1024 * 1024);
    return `${mb.toFixed(0)} MB`;
  }
}
