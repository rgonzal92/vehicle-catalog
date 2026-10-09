import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Button } from 'primeng/button';
import { Dialog } from 'primeng/dialog';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { TableModule } from 'primeng/table';
import { Tag } from 'primeng/tag';
import { Role } from '../../../core/session';
import { reasonOf } from '../../../shared/reason-of';
import { ListedUser, ROLE_NAMES, Users } from './users';

/** Where an admin sees the people who use the app and changes their roles. */
@Component({
  imports: [DatePipe, ReactiveFormsModule, Button, Dialog, Message, Select, TableModule, Tag],
  selector: 'app-users-page',
  template: `
    <div class="max-w-5xl">
      <div class="surface">
        <p-table [value]="users.users()">
          <ng-template #header>
            <tr>
              <th scope="col">Username</th>
              <th scope="col">Email</th>
              <th scope="col">Role</th>
              <th scope="col">Last login</th>
              <th scope="col"><span class="sr-only">Actions</span></th>
            </tr>
          </ng-template>
          <ng-template #body let-user>
            <tr>
              <td>{{ user.username }}</td>
              <td>{{ user.email }}</td>
              <td><p-tag severity="secondary" [value]="roleName(user.role)" /></td>
              <td>{{ user.lastLogin ? (user.lastLogin | date: 'medium') : 'Never' }}</td>
              <td class="text-right whitespace-nowrap">
                @if (user.changeable) {
                  <p-button
                    label="Change role"
                    severity="secondary"
                    size="small"
                    [text]="true"
                    [ariaLabel]="'Change role of ' + user.username"
                    (onClick)="startChanging(user)"
                  />
                }
              </td>
            </tr>
          </ng-template>
          <ng-template #emptymessage>
            <tr>
              <td class="surface-empty" colspan="5">There are no users.</td>
            </tr>
          </ng-template>
        </p-table>
      </div>

      <p-dialog
        header="Change role"
        closeAriaLabel="Close"
        [modal]="true"
        [style]="{ width: '28rem' }"
        [(visible)]="dialogOpen"
      >
        <form class="grid gap-4" [formGroup]="form" (ngSubmit)="save()">
          @if (refusal()) {
            <p-message severity="error">{{ refusal() }}</p-message>
          }
          <p>
            Saving signs {{ changing()?.username }} out of the app. The role applies once they sign
            in again.
          </p>
          <div class="grid gap-1">
            <label id="user-role-label" for="user-role">Role</label>
            <p-select
              inputId="user-role"
              ariaLabelledBy="user-role-label"
              formControlName="role"
              optionLabel="name"
              optionValue="role"
              appendTo="body"
              [options]="roles"
            />
          </div>
          <div class="flex justify-end gap-2">
            <p-button label="Cancel" severity="secondary" (onClick)="dialogOpen.set(false)" />
            <p-button
              type="submit"
              label="Save"
              [disabled]="form.invalid || form.value.role === changing()?.role || saving()"
            />
          </div>
        </form>
      </p-dialog>
    </div>
  `,
})
export class UsersPage {
  protected readonly users = inject(Users);
  protected readonly roles = ROLE_NAMES;

  protected readonly dialogOpen = signal(false);

  /** The account the dialog is changing. */
  protected readonly changing = signal<ListedUser | null>(null);

  /** Why the backend refused the last save, shown in the dialog. */
  protected readonly refusal = signal('');

  /** Whether a change is on its way, so that a second click sends no second one. */
  protected readonly saving = signal(false);

  protected readonly form = inject(FormBuilder).group({
    role: [null as Role | null, Validators.required],
  });

  constructor() {
    void this.users.load();
  }

  protected roleName(role: Role | null): string {
    return ROLE_NAMES.find((named) => named.role === role)?.name ?? 'No role';
  }

  protected startChanging(user: ListedUser): void {
    this.changing.set(user);
    this.refusal.set('');
    this.form.reset({ role: user.role });
    this.dialogOpen.set(true);
  }

  protected async save(): Promise<void> {
    const user = this.changing();
    const { role } = this.form.getRawValue();
    if (!user || !role || this.saving()) {
      return;
    }

    this.saving.set(true);
    let refusal = '';
    try {
      await this.users.setRole(user.username, role);
    } catch (error) {
      refusal = reasonOf(error);
    }
    this.saving.set(false);

    // The answer is for the account that was open when Save was pressed. If the dialog has moved
    // on to another account since, it is left as it is.
    if (this.changing() === user) {
      this.refusal.set(refusal);
      this.dialogOpen.set(!!refusal);
    }
  }
}
