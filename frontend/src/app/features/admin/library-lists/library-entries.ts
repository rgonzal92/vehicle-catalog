import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { LibraryEntry, LibraryList } from './library-list';

/** What an admin gives to rename, move, activate, or deactivate an entry. */
export type EntryChange = Pick<LibraryEntry, 'name' | 'sortOrder' | 'active'>;

/**
 * The entries of one of the library's ordered lists, in the backend's order. The list is read again
 * after every change, since moving one entry renumbers the others.
 */
@Injectable()
export class LibraryEntries {
  private readonly http = inject(HttpClient);
  private readonly loaded = signal<LibraryEntry[]>([]);
  private path = '';

  readonly entries = this.loaded.asReadonly();

  async load(list: LibraryList): Promise<void> {
    this.path = list.path;
    await this.read();
  }

  async add(entry: Pick<LibraryEntry, 'code' | 'name'>): Promise<void> {
    await firstValueFrom(this.http.post(this.path, entry));
    await this.read();
  }

  async change(entry: LibraryEntry, change: EntryChange): Promise<void> {
    await firstValueFrom(this.http.put(`${this.path}/${entry.code ?? entry.id}`, change));
    await this.read();
  }

  private async read(): Promise<void> {
    this.loaded.set(await firstValueFrom(this.http.get<LibraryEntry[]>(this.path)));
  }
}
