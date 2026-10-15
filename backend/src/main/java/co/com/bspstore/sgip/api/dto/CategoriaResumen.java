package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.modelo.Categoria;

/**
 * No expone empresa_id. El cliente no necesita saber a qué empresa
 * pertenece lo que pidió —sale de su propia sesión— y publicarlo solo
 * invita a que alguien intente enviarlo de vuelta.
 */
public record CategoriaResumen(Integer id, String nombre, String descripcion) {

    public static CategoriaResumen de(Categoria c) {
        return new CategoriaResumen(c.getId(), c.getNombre(), c.getDescripcion());
    }
}
