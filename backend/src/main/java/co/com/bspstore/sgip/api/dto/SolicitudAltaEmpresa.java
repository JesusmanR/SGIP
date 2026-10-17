package co.com.bspstore.sgip.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Lo que hace falta para dar de alta una empresa (RF-01).
 *
 * Lleva en el mismo cuerpo los datos del comercio y los de su
 * administrador inicial, y eso es deliberado: el requisito dice que «la
 * empresa queda creada con su administrador inicial», de modo que son
 * una sola operación. Partirlo en dos llamadas dejaría, al fallar la
 * segunda, una empresa sin nadie que pueda entrar en ella —y nadie que
 * pueda crear a ese alguien, porque crear usuarios es cosa de su propio
 * administrador (RF-12)—.
 *
 * El plan es opcional. La columna tiene BASICO por omisión y obligar a
 * elegir un plan en el alta solo serviría para que quien registra
 * escriba «BASICO» a mano.
 *
 * No hay campo de estado. El estado inicial no lo elige quien registra:
 * es PRUEBA por la RN-14, y aceptarlo desde fuera permitiría dar de alta
 * una empresa ya activa saltándose el periodo de prueba.
 */
public record SolicitudAltaEmpresa(

        @NotBlank(message = "La identificación tributaria es obligatoria")
        @Size(max = 20, message = "La identificación tributaria no puede pasar de 20 caracteres")
        String nit,

        @NotBlank(message = "La razón social es obligatoria")
        @Size(max = 160, message = "La razón social no puede pasar de 160 caracteres")
        String razonSocial,

        @Email(message = "El correo de contacto no tiene forma de correo")
        @Size(max = 120, message = "El correo de contacto no puede pasar de 120 caracteres")
        String correoContacto,

        @Size(max = 20, message = "El teléfono no puede pasar de 20 caracteres")
        String telefono,

        @Size(max = 80, message = "La ciudad no puede pasar de 80 caracteres")
        String ciudad,

        @Size(max = 40, message = "El plan no puede pasar de 40 caracteres")
        String plan,

        @NotBlank(message = "El nombre del administrador es obligatorio")
        @Size(max = 120, message = "El nombre del administrador no puede pasar de 120 caracteres")
        String administradorNombre,

        @NotBlank(message = "El correo del administrador es obligatorio")
        @Email(message = "El correo del administrador no tiene forma de correo")
        @Size(max = 120, message = "El correo del administrador no puede pasar de 120 caracteres")
        String administradorCorreo,

        /**
         * La contraseña con la que entrará la primera vez. El mínimo de
         * ocho caracteres es el que ya aplica el inicializador del
         * administrador de arranque; no se inventa otro aquí.
         *
         * El usuario nace con {@code cambio_clave_req} en cierto, de modo
         * que esta clave sirve para entrar una vez y hay que cambiarla.
         * Por eso no se devuelve en la respuesta: quien la escribió ya la
         * conoce, y repetirla en el cuerpo de un 201 la dejaría escrita
         * en cualquier registro de peticiones.
         */
        @NotBlank(message = "La contraseña del administrador es obligatoria")
        @Size(min = 8, max = 100, message = "La contraseña debe tener al menos 8 caracteres")
        String administradorClave
) {}
