import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { sentiaBridge } from "./vite.bridge";

// Dev proxy avoids CORS: the frontend calls same-origin /api/* and Vite forwards
// to the backend (:8080) or the AI service (:8000). The AI service owns
// /api/assistant/* — matched first so it isn't swallowed by the /api rule.
export default defineConfig({
  plugins: [react(), sentiaBridge()],
  server: {
    port: 5173,
    proxy: {
      "/api/assistant": { target: "http://localhost:8000", changeOrigin: true },
      "/api": { target: "http://localhost:8080", changeOrigin: true },
    },
  },
});
