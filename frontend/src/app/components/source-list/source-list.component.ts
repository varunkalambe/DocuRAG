import { Component, input } from '@angular/core';
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
}
