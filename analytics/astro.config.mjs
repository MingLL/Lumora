import { defineConfig } from 'astro/config';

export default defineConfig({
  output: 'static',
  server: { port: 4322 },
  vite: {
    server: {
      proxy: { '/api/analytics': 'http://127.0.0.1:8080' }
    }
  }
});
