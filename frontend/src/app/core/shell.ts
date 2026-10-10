import {
  afterNextRender,
  Component,
  ElementRef,
  inject,
  Injector,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AngleDoubleLeft } from '@primeicons/angular/angle-double-left';
import { AngleDoubleRight } from '@primeicons/angular/angle-double-right';
import { Car } from '@primeicons/angular/car';
import { Comments } from '@primeicons/angular/comments';
import { Globe } from '@primeicons/angular/globe';
import { Home } from '@primeicons/angular/home';
import { List } from '@primeicons/angular/list';
import { ListCheck } from '@primeicons/angular/list-check';
import { Moon } from '@primeicons/angular/moon';
import { Sitemap } from '@primeicons/angular/sitemap';
import { Sun } from '@primeicons/angular/sun';
import { Tags } from '@primeicons/angular/tags';
import { User } from '@primeicons/angular/user';
import { Users } from '@primeicons/angular/users';
import { Button, ButtonDirective, ButtonIcon, ButtonLabel } from 'primeng/button';
import { Popover } from 'primeng/popover';
import {
  Sidebar,
  SidebarAside,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarLayout,
  SidebarMain,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarPanel,
  SidebarSpacer,
} from 'primeng/sidebar';
import { Tag } from 'primeng/tag';
import { filter } from 'rxjs';
import { ColorScheme } from './color-scheme';
import { NotificationsButton } from './notifications-button';
import { Session } from './session';

/** Where the browser keeps whether the sidebar is collapsed. */
const SIDEBAR = 'sidebar';

/**
 * The frame around every page a signed-in person with a role sees: a sidebar for moving between
 * pages, and a strip across the top with the page's title, the person's notifications, the switch
 * between light and dark, and the person's account. The page itself renders in the outlet.
 */
@Component({
  imports: [
    NotificationsButton,
    RouterLink,
    RouterLinkActive,
    RouterOutlet,
    AngleDoubleLeft,
    AngleDoubleRight,
    Car,
    Comments,
    Globe,
    Home,
    List,
    Moon,
    Sitemap,
    Sun,
    Tags,
    User,
    ListCheck,
    Users,
    Button,
    ButtonDirective,
    ButtonIcon,
    ButtonLabel,
    Popover,
    Sidebar,
    SidebarAside,
    SidebarContent,
    SidebarFooter,
    SidebarGroup,
    SidebarGroupContent,
    SidebarGroupLabel,
    SidebarHeader,
    SidebarLayout,
    SidebarMain,
    SidebarMenu,
    SidebarMenuButton,
    SidebarMenuItem,
    SidebarPanel,
    SidebarSpacer,
    Tag,
  ],
  selector: 'app-shell',
  template: `
    <a
      class="sr-only rounded-border bg-surface-0 text-primary focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:z-50 focus:px-3 focus:py-2 dark:bg-surface-900"
      href="#main"
      (click)="skip($event)"
      >Skip to content</a
    >
    <p-sidebar-layout>
      <p-sidebar collapsible="icon" [open]="expanded()">
        <p-sidebar-spacer />
        <!-- Fixed, so that it stays in view while the page scrolls. -->
        <p-sidebar-aside class="fixed" role="navigation" aria-label="Main">
          <p-sidebar-panel>
            <p-sidebar-header>
              <p class="flex items-center gap-2 font-semibold">
                <span
                  class="grid size-8 shrink-0 place-items-center rounded-border bg-primary text-sm text-primary-contrast"
                  aria-hidden="true"
                  >VC</span
                >
                <span class="truncate" [class.sr-only]="!expanded()">Vehicle Catalog</span>
              </p>
            </p-sidebar-header>
            <p-sidebar-content>
              <p-sidebar-group>
                <p-sidebar-group-content>
                  <p-sidebar-menu>
                    <p-sidebar-menu-item>
                      <!-- A catalog is reached from the dashboard, so its pages count as part of it. -->
                      <a
                        pSidebarMenuButton
                        routerLink="/dashboard"
                        title="Dashboard"
                        [isActive]="onCatalogPages()"
                        [attr.aria-current]="onCatalogPages() ? 'page' : null"
                      >
                        <svg data-p-icon="home" />
                        <span>Dashboard</span>
                      </a>
                    </p-sidebar-menu-item>
                    <p-sidebar-menu-item>
                      <a
                        #analyst="routerLinkActive"
                        pSidebarMenuButton
                        routerLink="/analyst"
                        routerLinkActive
                        ariaCurrentWhenActive="page"
                        title="Analyst"
                        [isActive]="analyst.isActive"
                      >
                        <svg data-p-icon="comments" />
                        <span>Analyst</span>
                      </a>
                    </p-sidebar-menu-item>
                  </p-sidebar-menu>
                </p-sidebar-group-content>
              </p-sidebar-group>
              @if (session.holds('admin')) {
                <p-sidebar-group>
                  <p-sidebar-group-label>Admin</p-sidebar-group-label>
                  <p-sidebar-group-content>
                    <p-sidebar-menu>
                      <p-sidebar-menu-item>
                        <a
                          #lines="routerLinkActive"
                          pSidebarMenuButton
                          routerLink="/admin/vehicle-lines"
                          routerLinkActive
                          ariaCurrentWhenActive="page"
                          title="Vehicle lines"
                          [isActive]="lines.isActive"
                        >
                          <svg data-p-icon="car" />
                          <span>Vehicle lines</span>
                        </a>
                      </p-sidebar-menu-item>
                      <p-sidebar-menu-item>
                        <a
                          #trims="routerLinkActive"
                          pSidebarMenuButton
                          routerLink="/admin/trims"
                          routerLinkActive
                          ariaCurrentWhenActive="page"
                          title="Trims"
                          [isActive]="trims.isActive"
                        >
                          <svg data-p-icon="tags" />
                          <span>Trims</span>
                        </a>
                      </p-sidebar-menu-item>
                      <p-sidebar-menu-item>
                        <a
                          #regions="routerLinkActive"
                          pSidebarMenuButton
                          routerLink="/admin/regions"
                          routerLinkActive
                          ariaCurrentWhenActive="page"
                          title="Regions"
                          [isActive]="regions.isActive"
                        >
                          <svg data-p-icon="globe" />
                          <span>Regions</span>
                        </a>
                      </p-sidebar-menu-item>
                      <p-sidebar-menu-item>
                        <a
                          #features="routerLinkActive"
                          pSidebarMenuButton
                          routerLink="/admin/features"
                          routerLinkActive
                          ariaCurrentWhenActive="page"
                          title="Feature library"
                          [isActive]="features.isActive"
                        >
                          <svg data-p-icon="list" />
                          <span>Feature library</span>
                        </a>
                      </p-sidebar-menu-item>
                      <p-sidebar-menu-item>
                        <a
                          #globalRules="routerLinkActive"
                          pSidebarMenuButton
                          routerLink="/admin/global-rules"
                          routerLinkActive
                          ariaCurrentWhenActive="page"
                          title="Global rules"
                          [isActive]="globalRules.isActive"
                        >
                          <svg data-p-icon="sitemap" />
                          <span>Global rules</span>
                        </a>
                      </p-sidebar-menu-item>
                      <p-sidebar-menu-item>
                        <a
                          #users="routerLinkActive"
                          pSidebarMenuButton
                          routerLink="/admin/users"
                          routerLinkActive
                          ariaCurrentWhenActive="page"
                          title="Users"
                          [isActive]="users.isActive"
                        >
                          <svg data-p-icon="users" />
                          <span>Users</span>
                        </a>
                      </p-sidebar-menu-item>
                      <p-sidebar-menu-item>
                        <a
                          #jobs="routerLinkActive"
                          pSidebarMenuButton
                          routerLink="/admin/jobs"
                          routerLinkActive
                          ariaCurrentWhenActive="page"
                          title="Jobs"
                          [isActive]="jobs.isActive"
                        >
                          <svg data-p-icon="list-check" />
                          <span>Jobs</span>
                        </a>
                      </p-sidebar-menu-item>
                    </p-sidebar-menu>
                  </p-sidebar-group-content>
                </p-sidebar-group>
              }
            </p-sidebar-content>
            <p-sidebar-footer>
              <button
                pButton
                type="button"
                severity="secondary"
                [text]="true"
                [iconOnly]="true"
                [attr.aria-label]="expanded() ? 'Collapse the sidebar' : 'Expand the sidebar'"
                [attr.aria-expanded]="expanded()"
                (click)="collapse()"
              >
                @if (expanded()) {
                  <svg data-p-icon="angle-double-left" pButtonIcon />
                } @else {
                  <svg data-p-icon="angle-double-right" pButtonIcon />
                }
              </button>
            </p-sidebar-footer>
          </p-sidebar-panel>
        </p-sidebar-aside>
      </p-sidebar>
      <p-sidebar-main class="min-w-0">
        <header
          class="sticky top-0 z-10 flex h-14 items-center gap-3 border-b border-surface bg-surface-0 px-6 dark:bg-surface-900"
        >
          <h1 #heading tabindex="-1" class="mr-auto truncate text-lg font-semibold outline-none">
            {{ title() }}
          </h1>
          <app-notifications-button />
          <button
            pButton
            type="button"
            severity="secondary"
            [text]="true"
            [iconOnly]="true"
            [attr.aria-label]="scheme.dark() ? 'Use light colors' : 'Use dark colors'"
            (click)="scheme.toggle()"
          >
            @if (scheme.dark()) {
              <svg data-p-icon="sun" pButtonIcon />
            } @else {
              <svg data-p-icon="moon" pButtonIcon />
            }
          </button>
          <p-tag data-role severity="secondary" [value]="session.role() ?? undefined" />
          <button
            pButton
            type="button"
            severity="secondary"
            [text]="true"
            (click)="account.toggle($event)"
          >
            <svg data-p-icon="user" pButtonIcon />
            <span pButtonLabel
              ><span class="sr-only">Account: </span>{{ session.person()?.name }}</span
            >
          </button>
          <p-popover #account ariaLabel="Account">
            <div class="grid gap-3">
              <div>
                <p class="font-semibold">{{ session.person()?.name }}</p>
                @if (session.person()?.email; as email) {
                  <p class="text-sm text-muted-color">{{ email }}</p>
                }
              </div>
              <p-button label="Sign out" severity="secondary" (onClick)="session.signOut()" />
            </div>
          </p-popover>
        </header>
        <main #main id="main" tabindex="-1" class="flex-1 p-6 outline-none">
          <router-outlet />
        </main>
      </p-sidebar-main>
    </p-sidebar-layout>
  `,
})
export class Shell {
  protected readonly session = inject(Session);
  protected readonly scheme = inject(ColorScheme);
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);

  private readonly heading = viewChild.required<ElementRef<HTMLElement>>('heading');
  private readonly main = viewChild.required<ElementRef<HTMLElement>>('main');

  /**
   * Whether the sidebar shows its words or its icons alone: as the person left it, and until they
   * have chosen, collapsed where the screen is too narrow for both the sidebar and a page.
   */
  protected readonly expanded = signal(
    (localStorage.getItem(SIDEBAR) ?? (window.innerWidth < 1024 ? 'collapsed' : 'expanded')) ===
      'expanded',
  );

  /** The title of the page in the outlet, which is its route's title without the app's name. */
  protected readonly title = signal(this.titleOfPage());

  /** Whether the page is the dashboard or one reached from it, which no other link leads to. */
  protected readonly onCatalogPages = signal(this.amongCatalogPages());

  constructor() {
    // The frame is made in the course of the navigation that first shows it, which then ends.
    let arriving = true;
    this.router.events
      .pipe(
        filter((event) => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe(() => {
        this.title.set(this.titleOfPage());
        this.onCatalogPages.set(this.amongCatalogPages());
        if (!arriving) {
          // Someone who follows a link hears and sees where it led.
          afterNextRender(() => this.heading().nativeElement.focus(), { injector: this.injector });
        }
        arriving = false;
      });
  }

  protected collapse(): void {
    this.expanded.update((expanded) => !expanded);
    localStorage.setItem(SIDEBAR, this.expanded() ? 'expanded' : 'collapsed');
  }

  /** Moves focus to the page. The link's own address would be resolved against the base address. */
  protected skip(event: Event): void {
    event.preventDefault();
    this.main().nativeElement.focus();
  }

  private amongCatalogPages(): boolean {
    return !/^\/(admin|analyst)\b/.test(this.router.url);
  }

  private titleOfPage(): string {
    let route = this.router.routerState.snapshot.root;
    while (route.firstChild) {
      route = route.firstChild;
    }
    return route.title?.split(' · ')[0] ?? '';
  }
}
