package co.com.bspstore.sgip.modelo;

/**
 * Lo que se puede anotar en {@code auditoria_plataforma} (V21).
 *
 * Los nombres son los del ENUM de la columna y el orden es el mismo, por
 * la misma razón que {@link EstadoEmpresa}: una cadena mal escrita la
 * rechaza el motor y no el compilador.
 *
 * Las dos restricciones de la tabla reparten estas acciones en dos
 * grupos, y el reparto no es cosmético:
 *
 * <ul>
 *   <li>Las de empresa exigen {@code empresa_id}; las nacionales lo
 *       exigen nulo, porque un cambio de tarifa no pertenece a ningún
 *       comercio y atribuirlo a uno sería mentir en la bitácora.</li>
 *   <li>Las de cambio exigen valor anterior; las de alta no lo tienen.</li>
 * </ul>
 *
 * {@link #esDeEmpresa()} y {@link #esCambio()} dejan ese reparto en un
 * solo sitio de la capa de Java. Si alguien añade una acción al ENUM y
 * no la clasifica aquí, el método falla al compilar por el switch
 * exhaustivo, que es antes de que el CHECK la rechace en ejecución.
 */
public enum AccionPlataforma {

    EMPRESA_ALTA,
    EMPRESA_ACTIVACION,
    EMPRESA_DESACTIVACION,
    UVT_ALTA,
    UVT_CAMBIO,
    IMPUESTO_ALTA,
    IMPUESTO_CAMBIO;

    /** Si la acción recae sobre una empresa concreta. */
    public boolean esDeEmpresa() {
        return switch (this) {
            case EMPRESA_ALTA, EMPRESA_ACTIVACION, EMPRESA_DESACTIVACION -> true;
            case UVT_ALTA, UVT_CAMBIO, IMPUESTO_ALTA, IMPUESTO_CAMBIO -> false;
        };
    }

    /** Si la acción modifica algo que ya existía, y por tanto tiene valor anterior. */
    public boolean esCambio() {
        return switch (this) {
            case EMPRESA_ACTIVACION, EMPRESA_DESACTIVACION, UVT_CAMBIO, IMPUESTO_CAMBIO -> true;
            case EMPRESA_ALTA, UVT_ALTA, IMPUESTO_ALTA -> false;
        };
    }
}
