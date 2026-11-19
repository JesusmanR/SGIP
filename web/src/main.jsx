import React from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
// Fuentes propias (ADR-0032): solo el subconjunto latino y los pesos que se usan.
import '@fontsource/dm-sans/latin-400.css'
import '@fontsource/dm-sans/latin-500.css'
import '@fontsource/dm-sans/latin-600.css'
import '@fontsource/dm-sans/latin-700.css'
import '@fontsource/manrope/latin-700.css'
import '@fontsource/manrope/latin-800.css'
// La base va primero: la hoja de cada pantalla llega con su página y tiene
// que quedar después, para ganar en igualdad de especificidad.
import './estilos/base.css'
import App from './App.jsx'

createRoot(document.getElementById('raiz')).render(
  <React.StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </React.StrictMode>,
)
