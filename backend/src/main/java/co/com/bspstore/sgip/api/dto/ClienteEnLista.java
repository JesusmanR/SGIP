package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.modelo.TipoDocumento;

import java.math.BigDecimal;

/**
 * Un cliente en la lista de la pantalla «Clientes».
 *
 * {@code saldoAFavor} es lo que la tienda le debe al cliente: el mismo valor que devuelve
 * {@code GET /caja/clientes/{id}/saldo}, y cero si nunca tuvo saldo. {@code autorizoDatos} dice si el
 * cliente ya autorizó el tratamiento de sus datos (el PUT de un cliente lo exige siempre, y la pantalla
 * muestra la casilla según lo guardado). {@code editable} es falso solo para el Consumidor final, que
 * gestiona el sistema.
 */
public record ClienteEnLista(
        Integer id,
        TipoDocumento tipoDocumento,
        String identificacion,
        String nombre,
        String telefono,
        String correo,
        String direccion,
        String ciudad,
        boolean autorizoDatos,
        BigDecimal saldoAFavor,
        boolean editable
) {}
