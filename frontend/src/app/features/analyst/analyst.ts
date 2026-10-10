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

/** A passage of an uploaded document that an answer marks with its number, as [1]. */
export interface Citation {
  number: number;
  documentId: number;
  /** The document's title. */
  title: string;
  passage: string;
}

/** The analyst's answer to a question. */
export interface AnalystAnswer {
  answer: string;
  toolCalls: ToolCall[];
  /** Whether it ended because it had taken as many requests to the model as one answer may. */
  stopped: boolean;
  /** The passages of documents the answer took from, when documents were chosen. */
  citations: Citation[];
}

/** A vehicle line's model year whose uploaded documents a conversation can be given to search. */
export interface DocumentsOf {
  vehicleLineId: number;
  modelYear: number;
}

/** One that has documents ready, as the choice of documents names it. */
export interface DocumentSubject extends DocumentsOf {
  vehicleLine: string;
  /** How many of its documents are ready. */
  documents: number;
}

/** Asks the analyst, a language model that answers from the Approved catalogs. */
@Injectable({ providedIn: 'root' })
export class Analyst {
  private readonly http = inject(HttpClient);

  /**
   * Asks the question a conversation ends with. The backend keeps none of the conversation, so
   * all of it is sent each time. A failure is the caller's to show, beside the question.
   *
   * @param documentsOf whose uploaded documents the answer may also be taken from, if any
   */
  ask(turns: AnalystTurn[], documentsOf: DocumentsOf | null = null): Promise<AnalystAnswer> {
    return firstValueFrom(
      this.http.post<AnalystAnswer>(
        '/api/analyst',
        documentsOf ? { turns, documentsOf } : { turns },
        { context: new HttpContext().set(IN_THE_BACKGROUND, true) },
      ),
    );
  }

  /**
   * Whose documents can be chosen: every vehicle line's model year that has a document ready.
   * When that cannot be found out there are none to choose, and the analyst answers as without.
   */
  async documents(): Promise<DocumentSubject[]> {
    try {
      return await firstValueFrom(
        this.http.get<DocumentSubject[]>('/api/analyst/documents', {
          context: new HttpContext().set(IN_THE_BACKGROUND, true),
        }),
      );
    } catch {
      return [];
    }
  }
}
