import { Component, input, signal } from '@angular/core';
import { CommonModule } from '@angular/common';

import { SourceMetadata } from '../../services/app-state.service';

@Component({
  selector: 'app-source-list',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './source-list.component.html',
  styleUrl: './source-list.component.css',
})
export class SourceListComponent {
  readonly sources = input<SourceMetadata[]>([]);
  readonly open = signal(false);

  toggle(): void {
    this.open.update((value) => !value);
  }

  pages(source: SourceMetadata): string {
    return source.start_page === source.end_page
      ? `p. ${source.start_page}`
      : `pp. ${source.start_page}–${source.end_page}`;
  }
}
