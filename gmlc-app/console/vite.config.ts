import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The build lands in the gateway's static resources and is served at
// /console/. Assets must be external files (no inline script or style) so the
// strict content-security policy stays intact; module-preload polyfill is
// disabled because it is the one script Vite would inline.
export default defineConfig({
  plugins: [react()],
  base: '/console/',
  server: {
    proxy: {
      '/v1': 'http://localhost:8082',
    },
  },
  build: {
    outDir: '../src/main/resources/static/console',
    emptyOutDir: true,
    cssCodeSplit: false,
    modulePreload: { polyfill: false },
    sourcemap: false,
  },
});
