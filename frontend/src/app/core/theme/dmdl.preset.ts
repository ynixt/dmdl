import { definePreset } from '@openng/optimus-ui-themes';
import Aura from '@openng/optimus-ui-themes/aura';

export const dmdlPreset = definePreset(Aura, {
  semantic: {
    primary: {
      50: '{violet.50}',
      100: '{violet.100}',
      200: '{violet.200}',
      300: '{violet.300}',
      400: '{violet.400}',
      500: '{violet.500}',
      600: '{violet.600}',
      700: '{violet.700}',
      800: '{violet.800}',
      900: '{violet.900}',
      950: '{violet.950}',
    },
    focusRing: {
      width: '2px',
      style: 'solid',
      color: '{primary.color}',
      offset: '2px',
      shadow: 'none',
    },
    colorScheme: {
      light: {
        primary: {
          color: '{violet.600}',
          contrastColor: '#ffffff',
          hoverColor: '{violet.700}',
          activeColor: '{violet.800}',
        },
        highlight: {
          background: '{violet.50}',
          focusBackground: '{violet.100}',
          color: '{violet.800}',
          focusColor: '{violet.900}',
        },
        surface: {
          0: '#ffffff',
          50: '{slate.50}',
          100: '{slate.100}',
          200: '{slate.200}',
          300: '{slate.300}',
          400: '{slate.400}',
          500: '{slate.500}',
          600: '{slate.600}',
          700: '{slate.700}',
          800: '{slate.800}',
          900: '{slate.900}',
          950: '{slate.950}',
        },
      },
      dark: {
        primary: {
          color: '{violet.300}',
          contrastColor: '{slate.950}',
          hoverColor: '{violet.200}',
          activeColor: '{violet.100}',
        },
        highlight: {
          background: 'color-mix(in srgb, {violet.400}, transparent 84%)',
          focusBackground: 'color-mix(in srgb, {violet.400}, transparent 76%)',
          color: '{violet.100}',
          focusColor: '{violet.50}',
        },
        surface: {
          0: '#ffffff',
          50: '{slate.50}',
          100: '{slate.100}',
          200: '{slate.200}',
          300: '{slate.300}',
          400: '{slate.400}',
          500: '{slate.500}',
          600: '{slate.600}',
          700: '{slate.700}',
          800: '{slate.800}',
          900: '{slate.900}',
          950: '{slate.950}',
        },
      },
    },
  },
  extend: {
    dmdl: {
      accent: {
        color: '{cyan.500}',
        hoverColor: '{cyan.400}',
      },
      status: {
        success: '{green.500}',
        warning: '{amber.500}',
        error: '{red.500}',
        info: '{cyan.500}',
      },
    },
  },
});

export const dmdlOptimusTheme = {
  preset: dmdlPreset,
  options: {
    darkModeSelector: '.dark-theme',
    cssLayer: {
      name: 'optimus',
      order: 'theme, base, optimus, components, utilities',
    },
  },
};
