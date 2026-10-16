package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.modelo.ProductoImagen;

/**
 * No expone la ruta de almacenamiento. El cliente recibe un enlace al
 * endpoint que la sirve, de modo que la organización interna de las
 * carpetas no sale al exterior y no hay nada que un cliente pueda
 * reescribir para pedir otro archivo.
 */
public record ImagenResumen(Integer id, String url, String urlMiniatura,
                            int orden, boolean principal, Integer bytes) {

    public static ImagenResumen de(ProductoImagen i) {
        return new ImagenResumen(i.getId(),
                "/api/v1/imagenes/" + i.getId(),
                "/api/v1/imagenes/" + i.getId() + "/miniatura",
                i.getOrden(), i.isPrincipal(), i.getBytes());
    }
}
