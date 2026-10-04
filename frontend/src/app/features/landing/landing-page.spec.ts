import { TestBed } from '@angular/core/testing';
import { LandingPage } from './landing-page';

describe('LandingPage', () => {
  it('shows the product name and a one-line description', async () => {
    const fixture = TestBed.createComponent(LandingPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    expect(page.querySelector('h1')?.textContent).toContain('Vehicle Catalog');
    expect(page.querySelector('p')?.textContent).toContain('each trim of a vehicle line');
  });
});
