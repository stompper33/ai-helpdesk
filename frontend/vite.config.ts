import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In development, Vite forwards /api to Spring Boot, so the browser sees one origin (no CORS).
// In Docker, nginx does the same job (see nginx.conf).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": { target: process.env.API_URL ?? "http://localhost:8080", changeOrigin: true },
    },
  },
});
