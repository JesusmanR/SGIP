const FORMAS = {
  vacio: <path d="M12 3 4 7v10l8 4 8-4V7l-8-4Zm0 0v18M4 7l8 4 8-4" />,
  error: <path d="M12 4 2.5 20h19L12 4Zm0 6v5m0 3v.01" />,
}

/** Icono decorativo de un estado: el texto de al lado es lo que informa. */
export default function IconoEstado({ tipo }) {
  return (
    <span className="estado-icono" aria-hidden="true">
      <svg viewBox="0 0 24 24" width="28" height="28" fill="none" stroke="currentColor"
        strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
        {FORMAS[tipo]}
      </svg>
    </span>
  )
}
