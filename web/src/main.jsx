import React from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
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
