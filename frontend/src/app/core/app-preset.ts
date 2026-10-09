import { definePreset } from '@primeuix/themes';
import Aura from '@primeuix/themes/aura';

/** The page's ground, which a surface stands off from in both color schemes. */
const GROUND = 'light-dark({surface.50}, {surface.950})';

/**
 * The app's look: Aura in blue, set in Inter, on cool grays in light and neutral ones in dark.
 *
 * White text on Aura's own primary, and its red text on an error message's background, fall short
 * of the 4.5:1 contrast WCAG AA requires. The primary color is a darker blue in light, and text in
 * an error message is darker in light and lighter in dark; these pass.
 *
 * A value for one color scheme alone is one argument of `light-dark()`, as Aura's own values are.
 */
export const AppPreset = definePreset(Aura, {
  semantic: {
    primary: {
      50: '{blue.50}',
      100: '{blue.100}',
      200: '{blue.200}',
      300: '{blue.300}',
      400: '{blue.400}',
      500: '{blue.500}',
      600: '{blue.600}',
      700: '{blue.700}',
      800: '{blue.800}',
      900: '{blue.900}',
      950: '{blue.950}',
      color: 'light-dark({primary.600}, {primary.400})',
      contrastColor: 'light-dark(#ffffff, {surface.900})',
      hoverColor: 'light-dark({primary.700}, {primary.300})',
      activeColor: 'light-dark({primary.800}, {primary.200})',
    },
    // The font is served by the app itself; styles.css says where from.
    typography: { fontFamily: 'Inter, ui-sans-serif, system-ui, sans-serif' },
  },
  components: {
    message: {
      error: {
        color: 'light-dark({red.700}, {red.400})',
      },
    },
    datatable: {
      headerCell: { padding: '0.5rem 0.75rem' },
      bodyCell: { padding: '0.5rem 0.75rem' },
    },
    sidebar: {
      layout: { background: GROUND },
      main: { background: GROUND },
      menuButton: { activeBackground: '{highlight.background}', activeColor: '{highlight.color}' },
    },
  },
});
