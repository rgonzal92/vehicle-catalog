import { definePreset } from '@primeuix/themes';
import Aura from '@primeuix/themes/aura';

/**
 * Aura with a darker primary color in light, and text in error messages that is darker in light
 * and lighter in dark. White text on Aura's own primary, and its red text on an error message's
 * background, fall short of the 4.5:1 contrast WCAG AA requires; these pass. In dark the primary
 * color is Aura's own.
 *
 * A value for one color scheme alone is one argument of `light-dark()`, as Aura's own values are.
 */
export const AppPreset = definePreset(Aura, {
  semantic: {
    primary: {
      color: 'light-dark({primary.700}, {primary.400})',
      contrastColor: 'light-dark(#ffffff, {surface.900})',
      hoverColor: 'light-dark({primary.800}, {primary.300})',
      activeColor: 'light-dark({primary.900}, {primary.200})',
    },
  },
  components: {
    message: {
      error: {
        color: 'light-dark({red.700}, {red.400})',
      },
    },
  },
});
