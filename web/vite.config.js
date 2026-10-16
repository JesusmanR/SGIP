import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// El front y el backend corren en puertos distintos durante el
// desarrollo. El proxy evita tener que habilitar CORS en el backend solo
// para trabajar en local: el navegador ve un único origen.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  test: {
    // Las pruebas se llaman *.prueba.js, no *.test.js: el resto del
    // proyecto está en español y el nombre del archivo también.
    include: ['src/**/*.prueba.{js,jsx}'],
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/prueba/preparar.js',
  },
})
