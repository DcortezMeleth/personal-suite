import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "path";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      // The shared design system is resolved from source, so there is no build
      // step between editing a component and seeing it here.
      "@delfin/ui": path.resolve(__dirname, "../../../libs/ui/src/index.ts"),
    },
  },
  server: {
    // delFIN holds 3000 and 8080.
    port: 3001,
    proxy: {
      "/api": {
        target: "http://localhost:8081",
        changeOrigin: true,
      },
      // /health sits at the root rather than under /api, so that a readiness
      // probe does not depend on the API routes being wired up. That means it
      // needs proxying explicitly — and the same applies to the nginx config
      // when belFER is eventually containerised.
      "/health": {
        target: "http://localhost:8081",
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: "dist",
  },
});
