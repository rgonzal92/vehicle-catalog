import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { Button } from 'primeng/button';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { Ai, AiAvailability } from '../../core/ai';
import { reasonOf } from '../../shared/reason-of';
import { Analyst, AnalystTurn, Citation, DocumentSubject, ToolCall } from './analyst';

/** A turn as the page shows it: an answer also has the tool calls it used and what it cites. */
interface ShownTurn extends AnalystTurn {
  toolCalls?: ToolCall[];
  stopped?: boolean;
  citations?: Citation[];
}

/**
 * Where anyone with a role asks, in their own words, what the Approved catalogs offer. A language
 * model answers from what it looks up with the application's tools, and each answer lists the tool
 * calls it used. Where documents were uploaded, the person can choose whose are searched as well,
 * and an answer then lists the passages it took from. The conversation lives in this page and is
 * gone when the page is left.
 */
@Component({
  imports: [ReactiveFormsModule, Button, InputText, Message, Select],
  selector: 'app-analyst-page',
  template: `
    <section class="surface max-w-3xl" aria-labelledby="analyst-conversation">
      <div class="surface-header">
        <h2 id="analyst-conversation" class="font-semibold">Conversation</h2>
        <p class="text-sm text-muted-color">
          A language model answers from the Approved catalogs, which it reads with the tools listed
          under each answer. The catalogs are the record. The conversation stays in this tab.
        </p>
      </div>
      <ol class="grid gap-4 px-4 py-3" aria-live="polite" data-conversation>
        @for (turn of turns(); track $index) {
          <li class="grid gap-1" [attr.data-by]="turn.by">
            <p class="text-sm font-semibold">{{ turn.by === 'PERSON' ? 'You' : 'Analyst' }}</p>
            <p class="whitespace-pre-wrap">{{ turn.text }}</p>
            @if (turn.stopped) {
              <p class="text-sm text-muted-color">
                The answer stopped here: it had looked up as much as one answer may.
              </p>
            }
            @if (turn.toolCalls?.length) {
              <p class="text-sm text-muted-color" [id]="'tool-calls-' + $index">Tool calls</p>
              <ul
                class="grid gap-1 text-sm text-muted-color"
                [attr.aria-labelledby]="'tool-calls-' + $index"
              >
                @for (call of turn.toolCalls; track $index) {
                  <li class="break-all">
                    <code>{{ call.tool }} {{ call.arguments }}</code>
                  </li>
                }
              </ul>
            }
            @if (turn.citations?.length) {
              <p class="text-sm text-muted-color" [id]="'citations-' + $index">Documents cited</p>
              <ul class="grid gap-1 text-sm" [attr.aria-labelledby]="'citations-' + $index">
                @for (citation of turn.citations; track citation.number) {
                  <li>
                    <details>
                      <summary class="cursor-pointer">
                        [{{ citation.number }}] {{ citation.title }}
                      </summary>
                      <p class="mt-1 whitespace-pre-wrap text-muted-color">
                        {{ citation.passage }}
                      </p>
                    </details>
                  </li>
                }
              </ul>
            }
          </li>
        } @empty {
          <li class="text-muted-color">
            Ask which trims a vehicle line has in a region, where a feature is standard, which rules
            name a feature, or what changed between two versions.
          </li>
        }
        @if (asking()) {
          <li class="text-muted-color">The analyst is looking it up.</li>
        }
      </ol>
      <form
        class="grid gap-1 border-t border-surface px-4 py-3"
        (submit)="$event.preventDefault(); ask()"
      >
        @if (subjects().length) {
          <div class="mb-2 grid gap-1">
            <label id="analyst-documents-label" for="analyst-documents">Documents of</label>
            <p-select
              inputId="analyst-documents"
              ariaLabelledBy="analyst-documents-label"
              optionLabel="name"
              optionValue="key"
              appendTo="body"
              [formControl]="documentsOf"
              [options]="choices()"
            />
          </div>
        }
        <label for="analyst-question">Your question</label>
        <div class="flex gap-2">
          <input
            pInputText
            id="analyst-question"
            class="min-w-0 grow"
            maxlength="500"
            autocomplete="off"
            aria-describedby="analyst-question-help"
            [formControl]="question"
          />
          <p-button
            type="submit"
            label="Ask"
            [loading]="asking()"
            [disabled]="!ai().available || !question.value.trim()"
          />
        </div>
        <p id="analyst-question-help" class="text-sm text-muted-color" data-analyst-help>
          @if (ai().available) {
            At most 500 characters.
          } @else if (ai().reason) {
            The analyst cannot be asked now. {{ ai().reason }}
          }
        </p>
        @if (failure(); as why) {
          <p-message severity="warn">{{ why }}</p-message>
        }
      </form>
    </section>
  `,
})
export class AnalystPage implements OnInit {
  private readonly analyst = inject(Analyst);
  private readonly aiService = inject(Ai);

  protected readonly question = new FormControl('', { nonNullable: true });

  /** The conversation so far, oldest first. */
  protected readonly turns = signal<ShownTurn[]>([]);

  /** Whether an answer is being waited for. */
  protected readonly asking = signal(false);

  /** Why the last question got no answer, when it got none. */
  protected readonly failure = signal<string | null>(null);

  /** Whether the model can be asked. Until that is known, it counts as one that cannot. */
  protected readonly ai = signal<AiAvailability>({ available: false, reason: null });

  /** Whose uploaded documents can be chosen. With none, the choice is not shown. */
  protected readonly subjects = signal<DocumentSubject[]>([]);

  /** Whose documents the next answer may be taken from as well, by its key, or none. */
  protected readonly documentsOf = new FormControl('', { nonNullable: true });

  /** What the choice of documents offers: none, or one of those that have documents. */
  protected readonly choices = computed(() => [
    { key: '', name: 'None' },
    ...this.subjects().map((subject) => ({
      key: keyOf(subject),
      name: `${subject.vehicleLine} ${subject.modelYear}`,
    })),
  ]);

  ngOnInit(): void {
    void this.aiService.availability().then((found) => this.ai.set(found));
    void this.analyst.documents().then((found) => this.subjects.set(found));
  }

  /**
   * Asks the question in the box, with the conversation before it. A question that gets no answer
   * goes back into the box, so that it can be asked again.
   */
  protected async ask(): Promise<void> {
    const text = this.question.value.trim();
    if (!text || !this.ai().available || this.asking()) {
      return;
    }
    const before = this.turns();
    this.turns.set([...before, { by: 'PERSON', text }]);
    this.question.reset();
    this.failure.set(null);
    this.asking.set(true);
    try {
      const chosen = this.subjects().find((one) => keyOf(one) === this.documentsOf.value);
      const said = await this.analyst.ask(
        this.turns().map(({ by, text }) => ({ by, text })),
        chosen ? { vehicleLineId: chosen.vehicleLineId, modelYear: chosen.modelYear } : null,
      );
      this.turns.update((turns) => [
        ...turns,
        {
          by: 'ANALYST',
          text: said.answer.trim() || 'No answer was given.',
          toolCalls: said.toolCalls,
          stopped: said.stopped,
          citations: said.citations,
        },
      ]);
    } catch (error) {
      this.turns.set(before);
      this.question.setValue(text);
      this.failure.set(`The analyst could not answer. ${reasonOf(error)}`);
      // Why it could not may be why it cannot be asked for now, such as an allowance that is spent.
      void this.aiService.availability().then((found) => this.ai.set(found));
    } finally {
      this.asking.set(false);
    }
  }
}

/** What tells one vehicle line's model year from another in the choice of documents. */
function keyOf(subject: DocumentSubject): string {
  return `${subject.vehicleLineId}:${subject.modelYear}`;
}
