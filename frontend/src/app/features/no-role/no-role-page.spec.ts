import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { NAVIGATE } from '../../core/session';
import { NoRolePage } from './no-role-page';

describe('NoRolePage', () => {
  it('says no role is assigned and offers only sign-out', async () => {
    const navigate = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: NAVIGATE, useValue: navigate },
      ],
    });
    const fixture = TestBed.createComponent(NoRolePage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    expect(page.querySelector('h1')?.textContent).toContain('No role assigned');
    expect(page.querySelectorAll('a').length).toBe(0);

    page.querySelector('button')?.click();
    TestBed.inject(HttpTestingController).expectOne('/api/logout').flush({ logoutUrl: '/bye' });
    await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith('/bye'));
  });
});
