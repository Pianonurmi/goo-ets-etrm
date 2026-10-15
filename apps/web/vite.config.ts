/// <reference types="vitest/config" />
import { mkdirSync, readdirSync, renameSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig, type Plugin } from 'vite';
import { helpPlugin } from './vite.help';

// 127.0.0.1, not localhost: the dev profile listens on IPv4 loopback only.
const backend = process.env.ETRM_API_URL ?? 'http://127.0.0.1:8080';
// The help manual and its screenshots live in the repository's docs/ (one source for the repo and the app).
const repositoryRoot = fileURLToPath(new URL('../..', import.meta.url));

/**
 * Source maps are built without a reference in the bundle ('hidden') and moved out of it, so the jar never serves
 * them: CI keeps build/sourcemaps as an artifact to read a client error report's stack against.
 */
function sourceMapsOutOfBundle(): Plugin {
  const dist = fileURLToPath(new URL('./build/dist', import.meta.url));
  const maps = fileURLToPath(new URL('./build/sourcemaps', import.meta.url));
  return {
    name: 'etrm-source-maps-out-of-bundle',
    apply: 'build',
    buildStart() {
      rmSync(maps, { recursive: true, force: true });
    },
    closeBundle() {
      for (const file of readdirSync(dist, { recursive: true, encoding: 'utf8' })) {
        if (!file.endsWith('.map')) continue;
        mkdirSync(dirname(join(maps, file)), { recursive: true });
        renameSync(join(dist, file), join(maps, file));
      }
    },
  };
}

// The UI is served by the API jar from the same origin (ADR 0025); in development Vite proxies the API.
export default defineConfig({
  plugins: [react(), helpPlugin(repositoryRoot), sourceMapsOutOfBundle()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    fs: { allow: [repositoryRoot] },
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
    sourcemap: 'hidden',
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
      exclude: ['src/shared/api/schema.d.ts', 'src/shared/auth/permissions.generated.ts', 'src/**/*.test.*'],
      reporter: ['text-summary', 'lcov'],
      reportsDirectory: 'build/coverage',
    },
  },
});
