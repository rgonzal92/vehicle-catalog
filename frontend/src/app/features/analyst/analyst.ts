import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { IN_THE_BACKGROUND } from '../../core/api-error-interceptor';

/** One thing said in a conversation with the analyst: by the person who asks, or in answer. */
export interface AnalystTurn {
  by: 'PERSON' | 'ANALYST';
  text: string;
}

/** A tool the model had the application use for an answer, and what it asked it with, as JSON. */
export interface ToolCall {
  tool: string;
  arguments: string;
}

/** The analyst's answer to a question. */
export interface AnalystAnswer {
  answer: string;
  toolCalls: ToolCall[];
  /** Whether it ended because it had taken as many requests to the model as one answer may. */
  stopped: boolean;
}

/** Asks the analyst, a language model that answers from the Approved catalogs. */
@Injectable({ providedIn: 'root' })
export class Analyst {
  private readonly http = inject(HttpClient);

  /**
   * Asks the question a conversation ends with. The backend keeps none of the conversation, so
   * all of it is sent each time. A failure is the caller's to show, beside the question.
   */
  ask(turns: AnalystTurn[]): Promise<AnalystAnswer> {
    return firstValueFrom(
      this.http.post<AnalystAnswer>(
        '/api/analyst',
        { turns },
        { context: new HttpContext().set(IN_THE_BACKGROUND, true) },
      ),
    );
  }
}
