import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AnalystPage } from './analyst-page';

describe('AnalystPage', () => {
  let backend: HttpTestingController;
  let fixture: ComponentFixture<AnalystPage>;
  let element: HTMLElement;

  const box = () => element.querySelector<HTMLInputElement>('#analyst-question')!;
  const askButton = () => element.querySelector<HTMLButtonElement>('button[type="submit"]')!;
  const words = (part: Element | null | undefined) =>
    part?.textContent?.replace(/\s+/g, ' ').trim();
  const turns = () =>
    Array.from(element.querySelectorAll('[data-conversation] > li[data-by]'), (turn) => ({
      by: turn.getAttribute('data-by'),
      said: words(turn.querySelector('p.whitespace-pre-wrap')),
      toolCalls: Array.from(turn.querySelectorAll('ul li'), words),
    }));

  /** Lets what the page is waiting on happen, and the page show it. */
  const settle = async () => {
    await new Promise((done) => setTimeout(done));
    await fixture.whenStable();
    fixture.detectChanges();
  };

  /** Opens the page with the model as the backend says it stands. */
  async function open(availability: object = { available: true, reason: null }): Promise<void> {
    fixture = TestBed.createComponent(AnalystPage);
    element = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    backend.expectOne('/api/ai').flush(availability);
    await settle();
  }

  /** Types a question and asks it. What is sent is the backend's to answer. */
  async function ask(question: string) {
    box().value = question;
    box().dispatchEvent(new Event('input'));
    fixture.detectChanges();
    askButton().click();
    await settle();

    return backend.expectOne('/api/analyst');
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('shows an answer with the tool calls it used, as plain text', async () => {
    await open();

    const asked = await ask('Which trims does the Compact SUV have in Asia?');
    expect(asked.request.body).toEqual({
      turns: [{ by: 'PERSON', text: 'Which trims does the Compact SUV have in Asia?' }],
    });
    expect(words(element.querySelector('[data-conversation]'))).toContain(
      'The analyst is looking it up.',
    );
    asked.flush({
      answer: 'Base and <b>Touring</b>.',
      toolCalls: [
        { tool: 'list_lineages', arguments: '{}' },
        { tool: 'get_approved_catalog', arguments: '{"catalogId": 3}' },
      ],
      stopped: false,
    });
    await settle();

    expect(turns()).toEqual([
      { by: 'PERSON', said: 'Which trims does the Compact SUV have in Asia?', toolCalls: [] },
      {
        by: 'ANALYST',
        said: 'Base and <b>Touring</b>.',
        toolCalls: ['list_lineages {}', 'get_approved_catalog {"catalogId": 3}'],
      },
    ]);
    expect(element.querySelector('[data-conversation] b')).toBeNull();
    expect(box().value).toBe('');
  });

  it('sends the conversation so far with the next question', async () => {
    await open();
    (await ask('What is there?')).flush({
      answer: 'Four lineages.',
      toolCalls: [],
      stopped: false,
    });
    await settle();

    const asked = await ask('And in Europe?');

    expect(asked.request.body).toEqual({
      turns: [
        { by: 'PERSON', text: 'What is there?' },
        { by: 'ANALYST', text: 'Four lineages.' },
        { by: 'PERSON', text: 'And in Europe?' },
      ],
    });
    asked.flush({ answer: 'Three.', toolCalls: [], stopped: false });
    await settle();
  });

  it('says that an answer stopped when it took as many requests as one answer may', async () => {
    await open();

    (await ask('Everything?')).flush({
      answer: '',
      toolCalls: [{ tool: 'list_lineages', arguments: '{}' }],
      stopped: true,
    });
    await settle();

    expect(turns()[1].said).toBe('No answer was given.');
    expect(words(element.querySelector('[data-conversation]'))).toContain(
      'The answer stopped here: it had looked up as much as one answer may.',
    );
  });

  it('says why when the model cannot be asked, and asks nothing', async () => {
    await open({ available: false, reason: 'No key for the model is set.' });

    expect(words(element.querySelector('[data-analyst-help]'))).toBe(
      'The analyst cannot be asked now. No key for the model is set.',
    );
    box().value = 'What is there?';
    box().dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(askButton().disabled).toBe(true);
  });

  it('puts a question that got no answer back into the box, and says why', async () => {
    await open();

    (await ask('What is there?')).flush(
      { code: 'AI_ALLOWANCE_SPENT', detail: "Today's allowance for the model is spent." },
      { status: 429, statusText: 'Too Many Requests' },
    );
    await settle();
    backend
      .expectOne('/api/ai')
      .flush({ available: false, reason: "Today's allowance for the model is spent." });
    await settle();

    expect(turns()).toEqual([]);
    expect(box().value).toBe('What is there?');
    expect(words(element.querySelector('p-message'))).toBe(
      "The analyst could not answer. Today's allowance for the model is spent.",
    );
    expect(words(element.querySelector('[data-analyst-help]'))).toBe(
      "The analyst cannot be asked now. Today's allowance for the model is spent.",
    );
  });
});
