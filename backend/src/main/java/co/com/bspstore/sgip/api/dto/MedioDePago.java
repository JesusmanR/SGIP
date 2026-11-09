package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.modelo.TipoMedio;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Un medio de pago de la empresa (RF-43).
 *
 * El identificador es nulo cuando el medio todavía no se ha escrito, que es
 * el caso de la lista que siembra el alta.
 */
public record MedioDePago(Integer id, String nombre, TipoMedio tipo, boolean activo) {

    /**
     * Si lo gestiona el sistema. Se deriva del tipo y viaja en la respuesta para
     * que la pantalla no ofrezca activar o desactivar lo que no se puede; no es
     * un componente del registro, de modo que no se guarda ni se construye.
     */
    @JsonProperty("deSistema")
    public boolean deSistema() {
        return tipo.esDeSistema();
    }
}
