package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.logica.CampoNoPermitidoException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Los datos de contacto que el administrador edita de su empresa (PUT /api/v1/empresa).
 *
 * Son una sustitución completa: lo que no llega, o llega vacío, queda en nulo. Los textos se recortan antes
 * de validar (en el constructor), de modo que el tope de largo se mide sobre lo que se guarda y un correo de
 * solo espacios es «sin correo», no un correo mal escrito.
 *
 * Lo que NO acepta, a propósito: el NIT, la razón social, el tipo de documento, la empresa. La empresa sale
 * del contexto de seguridad, nunca del cuerpo, y la identidad la cambia solo la plataforma. Spring Boot no
 * falla ante campos desconocidos, y {@code application.yml} no se versiona para pedírselo, de modo que el
 * rechazo se hace aquí: {@link JsonAnySetter} recoge cualquier clave que no sea de las cinco y
 * {@link #exigirSoloCamposConocidos()} la convierte en un 400 que nombra el campo.
 */
public record SolicitudDatosEmpresa(

        @Size(max = 150, message = "El nombre comercial no puede pasar de 150 caracteres")
        String nombreComercial,

        @Size(max = 200, message = "La dirección no puede pasar de 200 caracteres")
        String direccion,

        @Size(max = 80, message = "La ciudad no puede pasar de 80 caracteres")
        String ciudad,

        @Size(max = 20, message = "El teléfono no puede pasar de 20 caracteres")
        String telefono,

        @Email(message = "El correo de contacto no tiene forma de correo")
        @Size(max = 120, message = "El correo de contacto no puede pasar de 120 caracteres")
        String correoContacto,

        /** Las claves del cuerpo que no son de las cinco de arriba. Solo para rechazarlas. */
        @JsonAnySetter
        Map<String, Object> camposDesconocidos
) {

    public SolicitudDatosEmpresa {
        nombreComercial = limpio(nombreComercial);
        direccion = limpio(direccion);
        ciudad = limpio(ciudad);
        telefono = limpio(telefono);
        correoContacto = limpio(correoContacto);
        camposDesconocidos = camposDesconocidos == null
                ? Map.of() : Map.copyOf(new LinkedHashMap<>(camposDesconocidos));
    }

    public SolicitudDatosEmpresa(String nombreComercial, String direccion, String ciudad,
                                 String telefono, String correoContacto) {
        this(nombreComercial, direccion, ciudad, telefono, correoContacto, Map.of());
    }

    /** Un 400 que nombra el campo si el cuerpo trajo algo que no es de los cinco datos de contacto. */
    public void exigirSoloCamposConocidos() {
        if (!camposDesconocidos.isEmpty()) {
            throw new CampoNoPermitidoException(camposDesconocidos.keySet());
        }
    }

    /** Recorta y convierte el vacío en nulo, que es lo que las columnas admiten. */
    private static String limpio(String valor) {
        if (valor == null) {
            return null;
        }
        String recortado = valor.strip();
        return recortado.isEmpty() ? null : recortado;
    }
}
