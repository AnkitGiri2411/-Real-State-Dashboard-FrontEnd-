import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': '/src' },
  },
  server: {
    // 5175 is this product's port. Each product in the marketplace owns its
    // own, so several can be run side by side while comparing them.
    port: 5175,
  },
  preview: {
    // The port the production bundle is served on for acceptance runs.
    port: 4175,
  },
  build: {
    rollupOptions: {
      output: {
        /*
         * Split the libraries that change on their own release cadence away from the
         * application code, so a one-line change to a page does not invalidate the reader's
         * cached copy of React, the router, the animation runtime and the icon set.
         */
        manualChunks: {
          'vendor-react': ['react', 'react-dom', 'react-router-dom'],
          'vendor-motion': ['framer-motion'],
          'vendor-query': ['@tanstack/react-query', 'zustand'],
          'vendor-icons': ['lucide-react'],
        },
      },
    },
    chunkSizeWarningLimit: 400,
  },
})
