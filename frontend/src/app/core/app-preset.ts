import { definePreset } from '@primeuix/themes';
import Aura from '@primeuix/themes/aura';

/**
 * Aura with a darker primary color and darker text in error messages. White text on Aura's own
 * primary, and its red text on an error message's background, fall short of the 4.5:1 contrast
 * WCAG AA requires; these pass.
 */
export const AppPreset = definePreset(Aura, {
  semantic: {
    colorScheme: {
      light: {
        primary: {
          color: '{primary.700}',
          contrastColor: '#ffffff',
          hoverColor: '{primary.800}',
          activeColor: '{primary.900}',
        },
      },
    },
  },
  components: {
    message: {
      error: {
        color: 'light-dark({red.700}, {red.500})',
      },
    },
  },
});
