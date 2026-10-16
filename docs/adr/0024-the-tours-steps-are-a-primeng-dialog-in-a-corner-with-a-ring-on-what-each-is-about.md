# The tour's steps are a PrimeNG Dialog in a corner, with a ring on what each is about

The tour shows each step in a PrimeNG `Dialog`, not modal, fixed in the bottom-right corner of the screen. The element a step is about is scrolled into view and gets a ring, one CSS class drawn in the theme's primary colour. The offer of a tour is a PrimeNG `Toast`. No tour library is used.

The maintainer accepted this decision on 2026-10-16, with the rule that the app uses PrimeNG's components wherever one fits and its own code only where logic or styling needs it.

Whichever library were used, the app would write most of what a step needs itself: waiting for an element through a page load and the return from signing in, steps that depend on who is signed in, and giving up on an element that never appears. A library would save the popup's placement next to its element, and the highlight.

## Considered Options

These were read on 2026-10-16, from each package's code, licence, and documentation, for an Angular 22 app on PrimeNG 22.1.2:

- **driver.js** 1.10.1: MIT, 7.7 kB gzipped, no dependencies. Its popup has the role of a dialog and is titled, and it keeps Tab inside the popup and the element. Focus lands first on its Close button, so Enter on a new step would end the tour unless the app moves it. Whether focus returns to the page after a tour of several steps is unconfirmed: the element it returns to is recorded again at each step. Its issue #434 reports failures of axe, the accessibility check the browser tests run.
- **Shepherd.js** 15.3.0 and **Intro.js** 8.6.0: AGPL-3.0 or a commercial licence. The repository is MIT.
- **ngx-ui-tour-primeng** 4.0.2: MIT, made for Angular 22 and PrimeNG 22, with steps across routes. Its popup is a PrimeNG `Popover`, which in 22.1.2 neither keeps focus inside it nor is titled by the step, and which hides when its element's container scrolls or the window is resized. The popup would be rewritten.
- **PrimeNG `Popover` placed by the app** next to the element: the same gaps as above.
- **The Angular CDK's dialog**, placed next to the element: it would do everything the tour asks of a popup, but it would be the one component of its kind in the app not drawn by PrimeNG.
- **PrimeNG `Dialog` in a corner**, the decision. In 22.1.2 it has the role of a dialog, is titled by its header, keeps Tab inside it, and closes on Esc. It cannot be placed next to an element, only at a side or corner of the screen.

## Consequences

- The popup is not beside the element it is about, so the ring does the pointing.
- `Dialog` puts focus on the first element it can in its body, and nothing gives focus back when it closes. The tour turns that off, puts focus on "Next" at each step itself, and gives focus back when it ends.
- `Dialog` says it is modal (`aria-modal="true"`) while it is not. It keeps focus inside it all the same, so a screen reader is told what a keyboard finds.
- The dialog and the offer are loaded once the tour has something to show, so a visitor who never takes it does not load them.
- The question is worth asking again if the tour needs its popup beside the element, for an element a corner would hide, or if PrimeNG adds a component for tours.
