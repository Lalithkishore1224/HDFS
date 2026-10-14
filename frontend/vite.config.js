import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// The Spring Boot backend listens on 8080; proxying keeps the browser on a single origin.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    allowedHosts: ['.ngrok-free.dev'],
    // Everything the backend serves goes through one origin, so the session cookie and Google's
    // redirect (http://localhost:5173/login/oauth2/code/google) both land in the same place.
    // xfwd makes the proxy send the original host, so the backend builds Google's callback address
    // (and its post-login redirect) on :5173 rather than on its own port.
    proxy: {
      '/api': { target: 'http://localhost:8080', xfwd: true },
      '/oauth2': { target: 'http://localhost:8080', xfwd: true },
      '/login': { target: 'http://localhost:8080', xfwd: true },
    },
  },
});
