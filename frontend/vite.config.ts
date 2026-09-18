import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // The backend's risk stream (SSE) and future API calls.
      '/api': 'http://localhost:8080',
    },
  },
});
