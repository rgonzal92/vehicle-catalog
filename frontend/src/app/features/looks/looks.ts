import { definePreset } from '@primeuix/themes';
import Aura from '@primeuix/themes/aura';
import { AppPreset } from '../../core/app-preset';

const SHADES = [50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 950];

/** Every shade of one of Aura's colors, as the shades of the primary color. */
const shadesOf = (color: string) =>
  Object.fromEntries(SHADES.map((shade) => [shade, `{${color}.${shade}}`]));

/** The surface colors: one of Aura's grays for light and one for dark. */
const surfaces = (light: string, dark: string) => ({
  0: '#ffffff',
  ...Object.fromEntries(
    SHADES.map((shade) => [shade, `light-dark({${light}.${shade}}, {${dark}.${shade}})`]),
  ),
});

/** The page's ground, which a surface stands off from in both color schemes. */
const GROUND = 'light-dark({surface.50}, {surface.950})';

/** What every look keeps: text in an error message that passes 4.5:1 in both color schemes. */
const message = { error: { color: 'light-dark({red.700}, {red.400})' } };

/** Calm and neutral: blue on cool grays, Inter, moderate rounding and room. */
const Slate = definePreset(Aura, {
  semantic: {
    primary: {
      ...shadesOf('blue'),
      color: 'light-dark({primary.600}, {primary.400})',
      contrastColor: 'light-dark(#ffffff, {surface.900})',
      hoverColor: 'light-dark({primary.700}, {primary.300})',
      activeColor: 'light-dark({primary.800}, {primary.200})',
    },
    surface: surfaces('slate', 'zinc'),
    typography: { fontFamily: 'Inter, ui-sans-serif, system-ui, sans-serif', fontSize: '0.875rem' },
  },
  components: {
    message,
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

/** Dense, sharp, and technical: violet on neutral grays, IBM Plex Sans, a dark sidebar. */
const Graphite = definePreset(Aura, {
  primitive: { borderRadius: { xs: '1px', sm: '2px', md: '3px', lg: '4px', xl: '6px' } },
  semantic: {
    primary: {
      ...shadesOf('violet'),
      color: 'light-dark({primary.600}, {primary.400})',
      contrastColor: 'light-dark(#ffffff, {surface.900})',
      hoverColor: 'light-dark({primary.700}, {primary.300})',
      activeColor: 'light-dark({primary.800}, {primary.200})',
    },
    surface: surfaces('zinc', 'zinc'),
    typography: {
      fontFamily: "'IBM Plex Sans', ui-sans-serif, system-ui, sans-serif",
      fontSize: '0.8125rem',
    },
    formField: { paddingX: '0.5rem', paddingY: '0.25rem' },
  },
  components: {
    message,
    datatable: {
      headerCell: { padding: '0.3125rem 0.625rem' },
      bodyCell: { padding: '0.3125rem 0.625rem' },
    },
    sidebar: {
      root: { borderColor: '{surface.800}' },
      layout: { background: GROUND },
      main: { background: GROUND },
      panel: { background: '{surface.900}', color: '{surface.0}' },
      groupLabel: { color: '{surface.400}' },
      menuButton: {
        color: '{surface.200}',
        focusBackground: '{surface.800}',
        focusColor: '{surface.0}',
        activeBackground: '{surface.800}',
        activeColor: '{surface.0}',
        icon: { color: '{surface.400}', focusColor: '{surface.0}' },
      },
    },
  },
});

/** Warm, soft, and roomy: teal on warm grays, Figtree, round corners, a sidebar without a border. */
const Stone = definePreset(Aura, {
  primitive: { borderRadius: { xs: '4px', sm: '6px', md: '10px', lg: '12px', xl: '16px' } },
  semantic: {
    primary: {
      ...shadesOf('teal'),
      color: 'light-dark({primary.700}, {primary.400})',
      contrastColor: 'light-dark(#ffffff, {surface.900})',
      hoverColor: 'light-dark({primary.800}, {primary.300})',
      activeColor: 'light-dark({primary.900}, {primary.200})',
    },
    surface: surfaces('stone', 'stone'),
    typography: {
      fontFamily: 'Figtree, ui-sans-serif, system-ui, sans-serif',
      fontSize: '0.9375rem',
    },
    formField: { paddingX: '0.75rem', paddingY: '0.5rem' },
  },
  components: {
    message,
    datatable: {
      headerCell: { padding: '0.75rem 1rem' },
      bodyCell: { padding: '0.75rem 1rem' },
    },
    sidebar: {
      root: { borderColor: 'transparent' },
      layout: { background: GROUND },
      main: { background: GROUND },
      panel: { background: GROUND },
      menuButton: {
        activeBackground: 'light-dark({surface.0}, {surface.800})',
        activeColor: '{text.color}',
      },
    },
  },
});

/** The looks to choose from, with the app's look as it is for comparison. */
export const LOOKS = [
  { name: 'As it is', says: 'The theme as it ships, with one color changed.', preset: AppPreset },
  { name: 'Slate', says: 'Calm and neutral. Blue on cool grays, Inter at 14 px.', preset: Slate },
  {
    name: 'Graphite',
    says: 'Dense, sharp, technical. Violet on neutral grays, IBM Plex Sans at 13 px, a dark sidebar.',
    preset: Graphite,
  },
  {
    name: 'Stone',
    says: 'Warm, soft, roomy. Teal on warm grays, Figtree at 15 px, round corners.',
    preset: Stone,
  },
];
