export type Colors = typeof colors;
export type Typography = typeof typography;

export const colors = {
  primary: {
    50: "#eff6ff",
    100: "#dbeafe",
    500: "#2563eb",
    600: "#1d4ed8",
    700: "#1e3a5f",
    900: "#1e3a5f",
  },
  success: {
    50: "#f0fdf4",
    500: "#16a34a",
    600: "#15803d",
  },
  danger: {
    50: "#fef2f2",
    500: "#dc2626",
    600: "#b91c1c",
  },
  warning: {
    50: "#fffbeb",
    500: "#d97706",
    600: "#b45309",
  },
  neutral: {
    50: "#f8fafc",
    100: "#f1f5f9",
    200: "#e2e8f0",
    300: "#cbd5e1",
    400: "#94a3b8",
    500: "#64748b",
    700: "#334155",
    900: "#0f172a",
  },
} as const;

export const typography = {
  fontFamily: {
    sans: ["Inter", "system-ui", "sans-serif"],
    mono: ["JetBrains Mono", "Menlo", "monospace"],
  },
} as const;
