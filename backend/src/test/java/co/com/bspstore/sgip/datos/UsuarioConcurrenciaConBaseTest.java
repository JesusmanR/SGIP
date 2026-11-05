package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.logica.ServicioAutenticacion;
import co.com.bspstore.sgip.modelo.Usuario;
import co.com.bspstore.sgip.util.Seguridad;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Un ingreso fallido que llega mientras se cambia la contraseña no puede
 * devolver la contraseña a su valor anterior (M2).
 *
 * Las dos transacciones se entrelazan en un solo hilo y sin esperas, como en
 * {@link ProductoConcurrenciaTest}: la «A» es el ingreso fallido, que lee al
 * usuario y tarda; en mitad de ella se confirma el cambio de clave en una
 * transacción propia; después la «A» guarda. Por omisión Hibernate escribe todas
 * las columnas de la fila que leyó, y con ellas el {@code clave_hash} viejo:
 * quien conociera la clave anterior recuperaba el acceso.
 *
 * Necesita la base con el esquema aplicado: se excluye sin ella con
 * {@code -DexcludedGroups=conBase}. Crea su propia empresa y usuario y los
 * borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class UsuarioConcurrenciaConBaseTest {

    private static final String CLAVE = "SgipDev2026";
    private static final String NUEVA = "NuevaClave2026";

    @Autowired UsuarioRepositorio usuarios;
    @Autowired ServicioAutenticacion autenticacion;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager gestor;

    private TransactionTemplate transaccion;
    private TransactionTemplate transaccionAparte;
    private Integer empresaId;
    private Integer usuarioId;
    private String marca;

    @BeforeEach
    void prepararDatosPropios() {
        transaccion = new TransactionTemplate(gestor);
        transaccionAparte = new TransactionTemplate(gestor);
        transaccionAparte.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        marca = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        empresaId = plataforma.insertarEmpresa("U" + marca, "Prueba usuario " + marca,
                null, null, null);
        jdbc.sql("""
                INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash)
                VALUES (:empresa, (SELECT rol_id FROM roles WHERE nombre = 'ADMINISTRADOR'),
                        'Prueba', :correo, :hash)
                """)
                .param("empresa", empresaId)
                .param("correo", "conc-" + marca.toLowerCase() + "@ejemplo.co")
                .param("hash", Seguridad.derivar(CLAVE))
                .update();
        usuarioId = jdbc.sql("SELECT usuario_id FROM usuarios WHERE empresa_id = :empresa")
                .param("empresa", empresaId)
                .query(Integer.class).single();
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (empresaId == null) {
            return;
        }
        jdbc.sql("DELETE FROM auditoria_accesos WHERE correo LIKE :patron")
                .param("patron", "conc-" + marca.toLowerCase() + "@%").update();
        for (String tabla : java.util.List.of("usuarios", "empresas")) {
            jdbc.sql("DELETE FROM " + tabla + " WHERE empresa_id = :empresa")
                    .param("empresa", empresaId)
                    .update();
        }
    }

    private String hashEnBase() {
        return jdbc.sql("SELECT clave_hash FROM usuarios WHERE usuario_id = :id")
                .param("id", usuarioId).query(String.class).single();
    }

    private boolean cambioPendienteEnBase() {
        return jdbc.sql("SELECT cambio_clave_req FROM usuarios WHERE usuario_id = :id")
                .param("id", usuarioId).query(Boolean.class).single();
    }

    @Test
    void unIngresoFallidoSimultaneoNoDevuelveLaClaveAlValorAnterior() {
        transaccion.executeWithoutResult(a -> {
            // El ingreso fallido lee al usuario con la clave de siempre...
            Usuario leido = usuarios.findById(usuarioId).orElseThrow();

            // ...y mientras tanto se confirma el cambio de contraseña.
            transaccionAparte.executeWithoutResult(b ->
                    autenticacion.cambiarClave(usuarioId, CLAVE, NUEVA, "prueba"));

            leido.registrarIntentoFallido(5, LocalDateTime.now());
            usuarios.saveAndFlush(leido);
        });

        assertTrue(Seguridad.verificar(NUEVA, hashEnBase()),
                "guardar un intento fallido no puede escribir la clave que se leyó antes del cambio");
        assertFalse(Seguridad.verificar(CLAVE, hashEnBase()), "la clave anterior no puede volver");
        assertFalse(cambioPendienteEnBase(), "tampoco la obligación de cambiarla");
        assertEquals(1, jdbc.sql("SELECT intentos_fallidos FROM usuarios WHERE usuario_id = :id")
                .param("id", usuarioId).query(Integer.class).single(),
                "y el intento fallido sí queda contado");
    }
}
