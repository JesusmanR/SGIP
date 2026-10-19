package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.MovimientoDeInventario;
import co.com.bspstore.sgip.api.dto.SolicitudAjuste;
import co.com.bspstore.sgip.api.dto.SolicitudIngreso;
import co.com.bspstore.sgip.logica.ServicioInventario;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * El inventario de la propia empresa (RF-48, RF-49).
 *
 * La lectura la tiene cualquier usuario de la empresa: el vendedor
 * necesita saber qué hay en bodega antes de prometer una entrega, y es
 * información de su propio negocio.
 *
 * La escritura es del administrador. El informe le atribuye el
 * inventario a ese rol y no se lo concede al vendedor, de modo que
 * conceder aquí un permiso que el documento no concede sería peor que
 * retenerlo. Si Black Steel Parts decide que quien recibe mercancía en
 * bodega es el vendedor, es una palabra en la anotación del ingreso —el
 * ajuste conviene que siga siendo del administrador, porque es la única
 * operación en la que una persona reescribe el saldo sin que se mueva
 * mercancía.
 *
 * Ningún método recibe la empresa: sale de la sesión.
 */
@RestController
@RequestMapping("/api/v1/inventario")
public class InventarioControlador {

    private static final int LIMITE_POR_OMISION = 100;
    private static final int LIMITE_MAXIMO = 500;

    private final ServicioInventario servicio;

    public InventarioControlador(ServicioInventario servicio) {
        this.servicio = servicio;
    }

    /** El kárdex de una referencia, del movimiento más reciente al más antiguo. */
    @GetMapping("/productos/{productoId}/movimientos")
    public List<MovimientoDeInventario> kardex(
            @PathVariable Integer productoId,
            @RequestParam(required = false) Integer limite) {
        return servicio.kardex(productoId, acotar(limite));
    }

    /** Los últimos movimientos de la empresa, para la pantalla de inventario. */
    @GetMapping("/movimientos")
    public List<MovimientoDeInventario> ultimos(@RequestParam(required = false) Integer limite) {
        return servicio.ultimos(acotar(limite));
    }

    /**
     * La comprobación del RF-49 sobre una referencia: su existencia y la
     * suma de sus movimientos, para que la pantalla pueda mostrar si
     * cuadran en lugar de que haya que creerlo.
     */
    @GetMapping("/productos/{productoId}/cuadre")
    public ResponseEntity<Cuadre> cuadre(@PathVariable Integer productoId) {
        List<MovimientoDeInventario> ultimo = servicio.kardex(productoId, 1);
        if (ultimo.isEmpty()) {
            // Sin movimientos no se puede afirmar nada sobre esta
            // referencia por esta vía: puede ser ajena, o puede estar en
            // cero y sin historia. El cuadre de todo el catálogo lo
            // resuelve database/verificar_kardex.sh, que sí ve productos.
            return ResponseEntity.notFound().build();
        }
        int suma = servicio.sumaDeMovimientos(productoId);
        int existencia = ultimo.get(0).existenciaPosterior();
        return ResponseEntity.ok(new Cuadre(productoId, existencia, suma, existencia == suma));
    }

    /** RF-48. Una cantidad menor o igual a cero la rechaza la validación con 400. */
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @PostMapping("/ingresos")
    public ResponseEntity<MovimientoDeInventario> ingresar(
            @Valid @RequestBody SolicitudIngreso solicitud) {
        return servicio.ingresar(solicitud)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * RF-49. Tres desenlaces, y el tercero merece su propio código:
     * 404 si el producto no es de esta empresa, 200 con el movimiento si
     * el conteo difería, y 204 si el conteo coincidía. Un 204 dice «no
     * había nada que registrar», que es la verdad; devolver 200 con un
     * cuerpo vacío o 404 harían creer otra cosa.
     */
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @PostMapping("/ajustes")
    public ResponseEntity<MovimientoDeInventario> ajustar(
            @Valid @RequestBody SolicitudAjuste solicitud) {
        var resultado = servicio.ajustar(solicitud);
        if (!resultado.existe()) {
            return ResponseEntity.notFound().build();
        }
        return resultado.huboCambio()
                ? ResponseEntity.ok(resultado.movimiento())
                : ResponseEntity.noContent().build();
    }

    private static int acotar(Integer limite) {
        if (limite == null || limite <= 0) {
            return LIMITE_POR_OMISION;
        }
        return Math.min(limite, LIMITE_MAXIMO);
    }

    /** La existencia frente a la suma de sus movimientos (RF-49). */
    public record Cuadre(Integer productoId, int existencia, int sumaDeMovimientos,
                         boolean cuadra) {}
}
