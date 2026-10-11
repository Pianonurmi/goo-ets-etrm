/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// 127.0.0.1, not localhost: the dev profile listens on IPv4 loopback only.
const backend = process.env.ETRM_API_URL ?? 'http://127.0.0.1:8080';

// The UI is served by the API jar from the same origin (ADR 0025); in development Vite proxies the API.
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': backend,
      '/actuator': backend,
      '/v3': backend,
      '/swagger-ui': backend,
    },
  },
  build: {
    outDir: 'build/dist',
    emptyOutDir: true,
    sourcemap: false,
    chunkSizeWarningLimit: 1500,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
    // CI runners are slower than laptops; the shell test renders the whole app.
    testTimeout: 15_000,
    coverage: {
      provider: 'v8',
      include: ['src/shared/**', 'src/features/**/model/**'],
      exclude: ['src/shared/api/schema.d.ts', 'src/**/*.test.*'],
      reporter: ['text-summary', 'lcov'],
      reportsDirectory: 'build/coverage',
    },
  },
});
