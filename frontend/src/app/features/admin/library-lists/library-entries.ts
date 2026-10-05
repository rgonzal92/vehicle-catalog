import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { LibraryEntry, LibraryList } from './library-list';

/** What an admin gives to rename, move, activate, or deactivate an entry. */
export type EntryChange = Pick<LibraryEntry, 'name' | 'sortOrder' | 'active'>;

/**
 * The entries of one of the library's ordered lists, in the backend's order. The list is read again
 * after every change, since moving one entry renumbers the others. A refused request leaves it as
 * it was.
 */
@Injectable()
export class LibraryEntries {
  private readonly http = inject(HttpClient);
  private readonly loaded = signal<LibraryEntry[]>([]);
  private list!: LibraryList;

  readonly entries = this.loaded.asReadonly();

  async load(list: LibraryList): Promise<void> {
    this.list = list;
    await this.read();
  }

  async add(entry: Pick<LibraryEntry, 'code' | 'name'>): Promise<void> {
    await firstValueFrom(this.http.post(this.list.path, entry));
    await this.read();
  }

  async change(entry: LibraryEntry, change: EntryChange): Promise<void> {
    await firstValueFrom(this.http.put(`${this.list.path}/${this.list.keyOf(entry)}`, change));
    await this.read();
  }

  private async read(): Promise<void> {
    this.loaded.set(await firstValueFrom(this.http.get<LibraryEntry[]>(this.list.path)));
  }
}
