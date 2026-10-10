import { DatePipe } from '@angular/common';
import { Component, computed, ElementRef, inject, OnInit, signal, viewChild } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Button } from 'primeng/button';
import { Dialog } from 'primeng/dialog';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { TableModule } from 'primeng/table';
import { Tag } from 'primeng/tag';
import { FixedLists } from '../../../core/fixed-lists';
import { VehicleLines } from '../../../core/vehicle-lines';
import { Loading, ReadFailed } from '../../../shared/read-state';
import { reasonOf } from '../../../shared/reason-of';
import {
  DOCUMENT_STATUS_NAMES,
  Documents,
  DocumentStatus,
  LARGEST_DOCUMENT_BYTES,
  LONGEST_DOCUMENT_TITLE,
  UploadedDocument,
} from './documents';

/**
 * Where an admin uploads notes about a vehicle line's model year, sees the ones there are, and
 * deletes them. A document is a file of text, Markdown, or PDF, and what may be uploaded is
 * limited: the form says how.
 */
@Component({
  imports: [
    DatePipe,
    ReactiveFormsModule,
    Button,
    Dialog,
    InputText,
    Message,
    Select,
    TableModule,
    Tag,
    Loading,
    ReadFailed,
  ],
  selector: 'app-documents-page',
  template: `
    <div class="grid max-w-6xl gap-6">
      <section class="surface" aria-labelledby="upload-a-document">
        <div class="surface-header">
          <h2 id="upload-a-document" class="font-semibold">Upload a document</h2>
          <p id="document-limits" class="text-sm text-muted-color">
            A .md, .txt, or .pdf file of at most 2 MB. There are at most 20 documents.
          </p>
        </div>
        <form class="grid gap-4 px-4 py-3 md:grid-cols-2" [formGroup]="form" (ngSubmit)="upload()">
          @if (refusal()) {
            <p-message class="md:col-span-2" severity="error">{{ refusal() }}</p-message>
          }
          <div class="grid gap-1">
            <label for="document-file">File</label>
            <input
              #chooser
              id="document-file"
              type="file"
              class="rounded-border border border-surface p-2"
              accept=".md,.txt,.pdf"
              aria-describedby="document-limits"
              (change)="chosen($event)"
            />
          </div>
          <div class="grid gap-1">
            <label for="document-title">Title</label>
            <input
              pInputText
              id="document-title"
              formControlName="title"
              autocomplete="off"
              [maxlength]="longestTitle"
            />
          </div>
          <div class="grid gap-1">
            <label id="document-line-label" for="document-line">Vehicle line</label>
            <p-select
              inputId="document-line"
              ariaLabelledBy="document-line-label"
              formControlName="vehicleLineId"
              optionLabel="name"
              optionValue="id"
              appendTo="body"
              [options]="lines()"
            />
          </div>
          <div class="grid gap-1">
            <label id="document-year-label" for="document-year">Model year</label>
            <p-select
              inputId="document-year"
              ariaLabelledBy="document-year-label"
              formControlName="modelYear"
              appendTo="body"
              [options]="fixedLists.modelYears()"
            />
          </div>
          <div class="flex justify-end md:col-span-2">
            <p-button
              type="submit"
              label="Upload"
              [disabled]="form.invalid || !file()"
              [loading]="uploading()"
            />
          </div>
        </form>
      </section>

      <section class="surface" aria-labelledby="the-documents">
        <div class="surface-header">
          <h2 id="the-documents" class="font-semibold">Documents</h2>
        </div>
        @if (failed()) {
          <app-read-failed class="m-4" what="The documents could not be read." (again)="load()" />
        } @else if (documents() === null) {
          <app-loading />
        } @else {
          <p-table [value]="documents() ?? []">
            <ng-template #header>
              <tr>
                <th scope="col">Title</th>
                <th scope="col">Vehicle line</th>
                <th scope="col">Model year</th>
                <th scope="col">File</th>
                <th scope="col">Uploaded</th>
                <th scope="col">Status</th>
                <th scope="col"><span class="sr-only">Actions</span></th>
              </tr>
            </ng-template>
            <ng-template #body let-document>
              <tr [attr.data-document]="document.id">
                <td>{{ document.title }}</td>
                <td>{{ document.vehicleLine }}</td>
                <td>{{ document.modelYear }}</td>
                <td class="break-all">{{ document.fileName }}, {{ sizeOf(document) }}</td>
                <td>{{ document.uploadedBy }}, {{ document.uploadedAt | date: 'medium' }}</td>
                <td>
                  <p-tag
                    [severity]="severityOf(document.status)"
                    [value]="statusName(document.status)"
                  />
                  @if (document.reason) {
                    <p class="mt-1 text-sm">{{ document.reason }}</p>
                  }
                </td>
                <td class="text-right whitespace-nowrap">
                  <p-button
                    label="Delete"
                    severity="secondary"
                    size="small"
                    [text]="true"
                    [ariaLabel]="'Delete the document: ' + document.title"
                    (onClick)="askToDelete(document)"
                  />
                </td>
              </tr>
            </ng-template>
            <ng-template #emptymessage>
              <tr>
                <td class="surface-empty" colspan="7">There are no documents.</td>
              </tr>
            </ng-template>
          </p-table>
        }
      </section>

      <p-dialog
        header="Delete document"
        closeAriaLabel="Close"
        [modal]="true"
        [style]="{ width: '30rem' }"
        [closable]="!deletingNow()"
        [visible]="deleting() !== null"
        (visibleChange)="deleting.set(null)"
      >
        @if (deleting(); as asked) {
          <div class="grid gap-4">
            @if (deletionRefusal()) {
              <p-message severity="error">{{ deletionRefusal() }}</p-message>
            }
            <p data-question>Delete the document "{{ asked.title }}"? Its file goes with it.</p>
            <div class="flex justify-end gap-2">
              <p-button
                label="Keep"
                severity="secondary"
                [autofocus]="true"
                [disabled]="deletingNow()"
                (onClick)="deleting.set(null)"
              />
              <p-button label="Delete" [loading]="deletingNow()" (onClick)="delete(asked)" />
            </div>
          </div>
        }
      </p-dialog>
    </div>
  `,
})
export class DocumentsPage implements OnInit {
  private readonly service = inject(Documents);
  private readonly vehicleLines = inject(VehicleLines);
  protected readonly fixedLists = inject(FixedLists);

  protected readonly longestTitle = LONGEST_DOCUMENT_TITLE;

  private readonly chooser = viewChild.required<ElementRef<HTMLInputElement>>('chooser');

  /** The documents there are, or null until they have been read. */
  protected readonly documents = signal<UploadedDocument[] | null>(null);

  /** Whether the documents could not be read. */
  protected readonly failed = signal(false);

  /** The file that was chosen for the next upload, if one was. */
  protected readonly file = signal<File | null>(null);

  protected readonly uploading = signal(false);

  /** Why the last upload was refused, here or by the backend. */
  protected readonly refusal = signal('');

  /** The document the admin was asked about deleting, while the question is open. */
  protected readonly deleting = signal<UploadedDocument | null>(null);

  protected readonly deletingNow = signal(false);
  protected readonly deletionRefusal = signal('');

  protected readonly form = inject(FormBuilder).group({
    title: ['', [Validators.required, Validators.maxLength(LONGEST_DOCUMENT_TITLE)]],
    vehicleLineId: [null as number | null, Validators.required],
    modelYear: [null as number | null, Validators.required],
  });

  /** The vehicle lines a document can be about: the active ones. */
  protected readonly lines = computed(() =>
    this.vehicleLines.lines().filter((line) => line.active),
  );

  ngOnInit(): void {
    void this.load();
    // A failure to read either list has already been shown as a message.
    void Promise.all([this.vehicleLines.load(), this.fixedLists.load()]).catch(() => undefined);
  }

  protected async load(): Promise<void> {
    this.failed.set(false);
    try {
      this.documents.set(await this.service.list());
    } catch {
      this.failed.set(true);
    }
  }

  /** Takes the file that was chosen, and calls the document by its name until it has a title. */
  protected chosen(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0] ?? null;
    this.file.set(file);
    this.refusal.set('');
    if (file && !(this.form.controls.title.value ?? '').trim()) {
      this.form.controls.title.setValue(
        file.name.replace(/\.[^.]*$/, '').slice(0, LONGEST_DOCUMENT_TITLE),
      );
    }
  }

  protected async upload(): Promise<void> {
    const file = this.file();
    const { title, vehicleLineId, modelYear } = this.form.getRawValue();
    if (!file || vehicleLineId === null || modelYear === null || this.uploading()) {
      return;
    }
    if (file.size > LARGEST_DOCUMENT_BYTES) {
      // A file this large is not sent: the server would cut it off without a reason.
      this.refusal.set('A document is at most 2 MB.');
      return;
    }
    this.refusal.set('');
    this.uploading.set(true);
    try {
      await this.service.upload(file, title ?? '', vehicleLineId, modelYear);
      this.form.reset();
      this.file.set(null);
      this.chooser().nativeElement.value = '';
      await this.load();
    } catch (error) {
      this.refusal.set(reasonOf(error));
    } finally {
      this.uploading.set(false);
    }
  }

  protected askToDelete(document: UploadedDocument): void {
    this.deletionRefusal.set('');
    this.deleting.set(document);
  }

  protected async delete(document: UploadedDocument): Promise<void> {
    if (this.deletingNow()) {
      return;
    }
    this.deletingNow.set(true);
    try {
      await this.service.delete(document.id);
      this.deleting.set(null);
      await this.load();
    } catch (error) {
      this.deletionRefusal.set(reasonOf(error));
    } finally {
      this.deletingNow.set(false);
    }
  }

  protected statusName(status: DocumentStatus): string {
    return DOCUMENT_STATUS_NAMES[status];
  }

  protected severityOf(status: DocumentStatus): 'secondary' | 'info' | 'success' | 'warn' {
    return status === 'READY'
      ? 'success'
      : status === 'RUNNING'
        ? 'info'
        : status === 'FAILED'
          ? 'warn'
          : 'secondary';
  }

  /** A file's size as people read one: in kilobytes, or in megabytes from one megabyte up. */
  protected sizeOf(document: UploadedDocument): string {
    return document.sizeBytes < 1024 * 1024
      ? `${Math.max(1, Math.round(document.sizeBytes / 1024))} kB`
      : `${(document.sizeBytes / (1024 * 1024)).toFixed(1)} MB`;
  }
}
