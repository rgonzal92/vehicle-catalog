import {
  afterNextRender,
  Component,
  effect,
  ElementRef,
  inject,
  Injector,
  viewChild,
} from '@angular/core';
import { Copy } from '@primeicons/angular/copy';
import { Github } from '@primeicons/angular/github';
import { Linkedin } from '@primeicons/angular/linkedin';
import { MessageService } from 'primeng/api';
import { Button, ButtonDirective, ButtonIcon, ButtonLabel } from 'primeng/button';
import { Dialog } from 'primeng/dialog';
import { Fieldset } from 'primeng/fieldset';
import { Toast } from 'primeng/toast';
import { Tour } from './tour';

/** Where the code is, and where its author is, for the tour's last card. */
const CODE = 'https://github.com/rgonzal92/vehicle-catalog';
const AUTHOR = 'https://www.linkedin.com/in/rgonzal92';

/**
 * The tour as a visitor sees it: the offer on the landing page, and each step in a corner of the
 * screen, with focus on the button that goes on. It stays open from page to page.
 */
@Component({
  imports: [
    Copy,
    Github,
    Linkedin,
    Button,
    ButtonDirective,
    ButtonIcon,
    ButtonLabel,
    Dialog,
    Fieldset,
    Toast,
  ],
  selector: 'app-tour-dialog',
  template: `
    <p-toast key="tour" position="bottom-right" (onClose)="tour.decline()">
      <ng-template #message let-message>
        <div class="grid flex-1 gap-3">
          <div>
            <p class="font-semibold">{{ message.summary }}</p>
            <p>{{ message.detail }}</p>
          </div>
          <div class="flex gap-2">
            <p-button label="Take the tour" size="small" (onClick)="tour.start()" />
            <p-button
              label="No thanks"
              size="small"
              severity="secondary"
              (onClick)="tour.decline()"
            />
          </div>
        </div>
      </ng-template>
    </p-toast>

    @if (tour.step(); as step) {
      <p-dialog
        [header]="step.title"
        closeAriaLabel="End the tour"
        position="bottomright"
        [visible]="true"
        (visibleChange)="tour.end()"
        [modal]="false"
        [draggable]="false"
        [resizable]="false"
        [focusOnShow]="false"
        [style]="{ width: '26rem' }"
      >
        <div class="grid gap-4" data-tour-step [attr.data-step]="step.id">
          @if (tour.missing()) {
            <p>This step can't be shown right now.</p>
          } @else {
            <p>{{ tour.words() }}</p>
          }

          @if (step.note; as note) {
            <!-- A note starts collapsed on every step: each step has a fieldset of its own. -->
            @for (opened of [step]; track opened.id) {
              <p-fieldset legend="How it's built" [toggleable]="true" [collapsed]="true">
                <p class="grid gap-2">
                  <span>{{ note.words }}</span>
                  <a
                    class="text-primary underline"
                    [href]="note.link"
                    target="_blank"
                    rel="noopener"
                    >Read the record on GitHub</a
                  >
                </p>
              </p-fieldset>
            }
          }

          @if (step.id === 'sign-in' && tour.login(); as login) {
            <dl class="grid grid-cols-[auto_1fr] items-center gap-x-3 gap-y-1">
              <dt class="text-muted-color">Username</dt>
              <dd class="flex items-center gap-1">
                <span class="font-mono">{{ login.username }}</span>
                <button
                  pButton
                  type="button"
                  severity="secondary"
                  [text]="true"
                  [iconOnly]="true"
                  aria-label="Copy the username"
                  (click)="copy(login.username, 'Username')"
                >
                  <svg data-p-icon="copy" pButtonIcon />
                </button>
              </dd>
              @if (login.password; as password) {
                <dt class="text-muted-color">Password</dt>
                <dd class="flex items-center gap-1">
                  <span class="font-mono">{{ password }}</span>
                  <button
                    pButton
                    type="button"
                    severity="secondary"
                    [text]="true"
                    [iconOnly]="true"
                    aria-label="Copy the password"
                    (click)="copy(password, 'Password')"
                  >
                    <svg data-p-icon="copy" pButtonIcon />
                  </button>
                </dd>
              }
            </dl>
          }

          @if (step.id === 'card') {
            <div class="flex flex-wrap justify-end gap-2">
              <a pButton severity="secondary" [href]="code" target="_blank" rel="noopener">
                <svg data-p-icon="github" pButtonIcon />
                <span pButtonLabel>View the code</span>
              </a>
              <a pButton severity="secondary" [href]="author" target="_blank" rel="noopener">
                <svg data-p-icon="linkedin" pButtonIcon />
                <span pButtonLabel>LinkedIn</span>
              </a>
              <button #next pButton type="button" (click)="tour.end()">
                <span pButtonLabel>Keep exploring</span>
              </button>
            </div>
          } @else {
            <div class="flex items-center gap-2">
              @if (tour.number(); as number) {
                <span class="mr-auto text-sm text-muted-color"
                  >{{ number }} of {{ tour.total }}</span
                >
              }
              @if (tour.missing()) {
                <p-button label="End tour" severity="secondary" (onClick)="tour.end()" />
              } @else {
                <p-button
                  label="Skip tour"
                  severity="secondary"
                  [text]="true"
                  (onClick)="tour.end()"
                />
                @if (step.id !== 'about') {
                  <p-button label="Back" severity="secondary" (onClick)="tour.back()" />
                }
              }
              <button #next pButton type="button" (click)="tour.next()">
                <span pButtonLabel>{{ step.id === 'sign-in' ? 'Sign in' : 'Next' }}</span>
              </button>
            </div>
          }
        </div>
      </p-dialog>
    }
  `,
})
export class TourDialog {
  protected readonly tour = inject(Tour);
  private readonly messages = inject(MessageService);
  private readonly injector = inject(Injector);

  protected readonly code = CODE;
  protected readonly author = AUTHOR;

  private readonly next = viewChild<ElementRef<HTMLButtonElement>>('next');

  constructor() {
    // The toast hears of a message only once it is on the page, which is after this first runs.
    effect(() => {
      const offering = this.tour.offering();
      afterNextRender(
        () => {
          if (offering) {
            this.messages.add({
              key: 'tour',
              sticky: true,
              summary: 'New here?',
              detail:
                "Take a 3-minute tour of one real workflow, from a published catalog to a manager's review.",
            });
          } else {
            this.messages.clear('tour');
          }
        },
        { injector: this.injector },
      );
    });

    // Each step, as it opens, puts focus on the button that goes on. A step on another page does
    // so again once its element is drawn, since the page took the focus to its heading on arrival.
    effect(() => {
      this.tour.step();
      this.tour.target();
      this.tour.missing();
      afterNextRender(() => this.next()?.nativeElement.focus(), {
        injector: this.injector,
      });
    });
  }

  protected async copy(value: string, what: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(value);
      this.messages.add({
        severity: 'success',
        summary: `${what} copied`,
        life: 2000,
      });
    } catch {
      this.messages.add({
        severity: 'warn',
        summary: `${what} could not be copied`,
        life: 4000,
      });
    }
  }
}
