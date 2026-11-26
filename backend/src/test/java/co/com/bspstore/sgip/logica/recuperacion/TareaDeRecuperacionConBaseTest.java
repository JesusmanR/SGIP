package co.com.bspstore.sgip.logica.recuperacion;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.correo.EnviadorDeCorreo;
import co.com.bspstore.sgip.util.Hoy;
import co.com.bspstore.sgip.util.Seguridad;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La tarea que decide si se genera un código y lo envía (RF-65, §5.3 de la spec), contra la
 * base real.
 *
 * El ejecutor se reemplaza por uno que corre la tarea en el mismo hilo (y anota si había una
 * transacción abierta al entregarla) y el enviador por uno falso que guarda los mensajes. El
 * reloj de la aplicación es uno ajustable: por omisión, el instante real, porque la
 * {@code fecha} de la bitácora la pone la base con su propia hora y los conteos la comparan con
 * la del reloj. Las filas de la bitácora que arman los casos de tope llevan su fecha explícita.
 *
 * Necesita la base con el esquema aplicado: se excluye sin ella con
 * {@code -DexcludedGroups=conBase}. Crea su propia empresa y usuarios y los borra al terminar.
 */
@Tag("conBase")
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
class TareaDeRecuperacionConBaseTest {

    private static final String ASUNTO = "Tu código para recuperar la contraseña de SGIP";
    private static final Pattern TEXTO = Pattern.compile(
            "Tu código es (\\d{6})\\. Vence en 15 minutos\\. Si pediste más de uno, usa el último que recibas\\. "
                    + "Si no lo pediste, ignora este mensaje: tu contraseña no cambia\\.");

    /** Un reloj que se puede mover: la aplicación lo recibe como el bean {@code reloj}. */
    static final class RelojAjustable extends Clock {
        private volatile Instant instante = Instant.now();

        void fijar(Instant nuevo) {
            instante = nuevo;
        }

        void avanzar(Duration cuanto) {
            instante = instante.plus(cuanto);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zona) {
            return Clock.fixed(instante, zona);
        }

        @Override
        public Instant instant() {
            return instante;
        }
    }

    record Correo(String destino, String asunto, String texto) {}

    static final class EnviadorFalso implements EnviadorDeCorreo {
        final List<Correo> enviados = new CopyOnWriteArrayList<>();
        volatile RuntimeException fallo;

        @Override
        public void enviar(String destino, String asunto, String texto) {
            if (fallo != null) {
                throw fallo;
            }
            enviados.add(new Correo(destino, asunto, texto));
        }
    }

    /** Corre la tarea en el hilo de quien la entrega y anota si había una transacción abierta. */
    static final class EjecutorQueMira implements Executor {
        volatile Boolean transaccionAlEntregar;

        @Override
        public void execute(Runnable tarea) {
            transaccionAlEntregar = TransactionSynchronizationManager.isActualTransactionActive();
            tarea.run();
        }
    }

    @TestConfiguration
    static class Reemplazos {
        @Bean(name = "reloj")
        RelojAjustable reloj() {
            return new RelojAjustable();
        }

        @Bean(name = "enviadorDeCorreo")
        EnviadorFalso enviadorDeCorreo() {
            return new EnviadorFalso();
        }

        @Bean(name = "ejecutorDeCorreo")
        EjecutorQueMira ejecutorDeCorreo() {
            return new EjecutorQueMira();
        }
    }

    @Autowired TareaDeRecuperacion tarea;
    @Autowired ServicioRecuperacion servicio;
    @Autowired RelojAjustable reloj;
    @Autowired EnviadorFalso enviador;
    @Autowired EjecutorQueMira ejecutor;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transacciones;

    private String marca;
    private Integer empresaId;
    private Integer usuarioId;
    private String correo;
    private String origen;

    @BeforeEach
    void prepararDatosPropios() {
        reloj.fijar(Instant.now());
        enviador.enviados.clear();
        enviador.fallo = null;
        ejecutor.transaccionAlEntregar = null;
        marca = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase();
        empresaId = plataforma.insertarEmpresa("T" + marca.toUpperCase(), "Prueba recuperacion " + marca,
                null, null, null);
        correo = "rec3-" + marca + "@ejemplo.co";
        origen = "t3-" + marca;
        usuarioId = insertarUsuario(correo, true, false);
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (empresaId == null) {
            return;
        }
        jdbc.sql("DELETE FROM auditoria_accesos WHERE correo LIKE :patron OR origen LIKE :origen")
                .param("patron", "%" + marca + "%").param("origen", "%" + marca).update();
        // Los códigos se borran con su usuario (ON DELETE CASCADE).
        jdbc.sql("DELETE FROM usuarios WHERE empresa_id = :empresa").param("empresa", empresaId).update();
        jdbc.sql("DELETE FROM empresas WHERE empresa_id = :empresa").param("empresa", empresaId).update();
    }

    // ---------------------------------------------------------------- ayudas

    private Integer insertarUsuario(String correoDelUsuario, boolean activo, boolean bloqueado) {
        jdbc.sql("""
                INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash, activo, bloqueado, bloqueado_en)
                VALUES (:empresa, (SELECT rol_id FROM roles WHERE nombre = 'ADMINISTRADOR'),
                        'Prueba', :correo, 'no-sirve-para-ingresar', :activo, :bloqueado,
                        CASE WHEN :bloqueado THEN NOW() ELSE NULL END)
                """)
                .param("empresa", empresaId)
                .param("correo", correoDelUsuario)
                .param("activo", activo)
                .param("bloqueado", bloqueado)
                .update();
        return jdbc.sql("SELECT usuario_id FROM usuarios WHERE correo = :correo")
                .param("correo", correoDelUsuario).query(Integer.class).single();
    }

    private void bitacora(Integer usuario, String correoAuditado, String motivo, String deOrigen, LocalDateTime fecha) {
        jdbc.sql("""
                INSERT INTO auditoria_accesos (usuario_id, correo, exitoso, motivo, origen, fecha)
                VALUES (:usuario, :correo, 0, :motivo, :origen, :fecha)
                """)
                .param("usuario", usuario)
                .param("correo", correoAuditado)
                .param("motivo", motivo)
                .param("origen", deOrigen)
                .param("fecha", fecha)
                .update();
    }

    private LocalDateTime ahora() {
        return Hoy.ahora(reloj);
    }

    private void pedir() {
        tarea.procesar(correo, origen);
    }

    private List<Map<String, Object>> auditoriaDe(String correoAuditado) {
        return jdbc.sql("SELECT usuario_id, exitoso, motivo, origen FROM auditoria_accesos "
                        + "WHERE correo = :correo ORDER BY acceso_id")
                .param("correo", correoAuditado).query().listOfRows();
    }

    /** tinyint(1) llega como Boolean con el conector; el resto de los enteros, como Number. */
    private static int entero(Object valor) {
        return valor instanceof Boolean b ? (b ? 1 : 0) : ((Number) valor).intValue();
    }

    private int codigosDelUsuario() {
        return jdbc.sql("SELECT COUNT(*) FROM codigos_recuperacion WHERE usuario_id = :usuario")
                .param("usuario", usuarioId).query(Integer.class).single();
    }

    private String ultimoMotivo() {
        List<Map<String, Object>> filas = auditoriaDe(correo);
        return (String) filas.get(filas.size() - 1).get("motivo");
    }

    private void verificarSinEnvioNiCodigo(String motivo) {
        assertThat(enviador.enviados).isEmpty();
        assertThat(codigosDelUsuario()).isZero();
        List<Map<String, Object>> filas = auditoriaDe(correo);
        assertThat(filas).hasSize(1);
        assertThat(filas.get(0).get("motivo")).isEqualTo(motivo);
        assertThat(entero(filas.get(0).get("exitoso"))).isZero();
    }

    // ---------------------------------------------------------- el camino feliz

    @Test
    void existenteActivoRecibeCodigo() {
        pedir();

        assertThat(enviador.enviados).hasSize(1);
        Correo enviado = enviador.enviados.get(0);
        assertThat(enviado.destino()).isEqualTo(correo);
        assertThat(enviado.asunto()).isEqualTo(ASUNTO);
        Matcher m = TEXTO.matcher(enviado.texto());
        assertThat(m.matches()).as("el texto: %s", enviado.texto()).isTrue();
        String codigo = m.group(1);

        // La base guarda un resumen: nunca el código, y el resumen sirve para comprobarlo.
        String guardado = jdbc.sql("SELECT codigo_hash FROM codigos_recuperacion WHERE usuario_id = :usuario")
                .param("usuario", usuarioId).query(String.class).single();
        assertThat(guardado).isNotEqualTo(codigo).doesNotContain(codigo);
        assertThat(Seguridad.verificar(codigo, guardado)).isTrue();

        // Exactamente una fila de bitácora (RF-11), con éxito.
        List<Map<String, Object>> filas = auditoriaDe(correo);
        assertThat(filas).hasSize(1);
        assertThat(filas.get(0).get("motivo")).isEqualTo("RECUPERACION_SOLICITADA");
        assertThat(entero(filas.get(0).get("exitoso"))).isEqualTo(1);
        assertThat(filas.get(0).get("usuario_id")).isEqualTo(usuarioId);
        assertThat(filas.get(0).get("origen")).isEqualTo(origen);
    }

    @Test
    void elCodigoGuardadoLlevaElOrigenYNoHaSidoUsado() {
        pedir();

        Map<String, Object> fila = jdbc.sql("SELECT origen, usado_en, intentos FROM codigos_recuperacion "
                + "WHERE usuario_id = :usuario").param("usuario", usuarioId).query().singleRow();
        assertThat(fila.get("origen")).isEqualTo(origen);
        assertThat(fila.get("usado_en")).isNull();
        assertThat(entero(fila.get("intentos"))).isZero();
    }

    @Test
    void relojUtc() {
        // La JVM del servidor va en UTC: 15:00 UTC son las 10:00 en Bogotá y el código vence a las
        // 10:15, no cinco horas antes.
        reloj.fijar(Instant.parse("2026-11-26T15:00:00Z"));

        pedir();

        Map<String, Object> fila = jdbc.sql("SELECT creado_en, expira FROM codigos_recuperacion "
                + "WHERE usuario_id = :usuario").param("usuario", usuarioId).query().singleRow();
        assertThat(fila.get("creado_en")).isEqualTo(LocalDateTime.of(2026, 11, 26, 10, 0, 0));
        assertThat(fila.get("expira")).isEqualTo(LocalDateTime.of(2026, 11, 26, 10, 15, 0));
    }

    // -------------------------------------------------------- lo que se descarta

    @Test
    void inexistente() {
        String otro = "rec3-nadie-" + marca + "@ejemplo.co";

        tarea.procesar(otro, origen);

        assertThat(enviador.enviados).isEmpty();
        List<Map<String, Object>> filas = auditoriaDe(otro);
        assertThat(filas).hasSize(1);
        assertThat(filas.get(0).get("motivo")).isEqualTo("RECUPERACION_CORREO_INEXISTENTE");
        assertThat(entero(filas.get(0).get("exitoso"))).isZero();
        assertThat(filas.get(0).get("usuario_id")).isNull();
    }

    @Test
    void inactivo() {
        jdbc.sql("UPDATE usuarios SET activo = 0 WHERE usuario_id = :id").param("id", usuarioId).update();

        pedir();

        verificarSinEnvioNiCodigo("RECUPERACION_CUENTA_INACTIVA");
    }

    @Test
    void bloqueadoSiRecibe() {
        jdbc.sql("UPDATE usuarios SET bloqueado = 1, bloqueado_en = NOW(), intentos_fallidos = 5 "
                + "WHERE usuario_id = :id").param("id", usuarioId).update();

        pedir();

        // Así se desbloquea: el bloqueo no impide pedir el código, ni se consulta su vencimiento.
        assertThat(enviador.enviados).hasSize(1);
        assertThat(codigosDelUsuario()).isEqualTo(1);
        assertThat(ultimoMotivo()).isEqualTo("RECUPERACION_SOLICITADA");
    }

    @Test
    void cuartoEnQuinceMinutos() {
        pedir();
        pedir();
        pedir();
        assertThat(enviador.enviados).hasSize(3);

        pedir();

        assertThat(enviador.enviados).hasSize(3);
        assertThat(codigosDelUsuario()).isEqualTo(3);
        assertThat(ultimoMotivo()).isEqualTo("RECUPERACION_LIMITE");
        // Cada solicitud deja una sola fila: 3 solicitadas y 1 de límite.
        assertThat(auditoriaDe(correo)).hasSize(4);
    }

    @Test
    void elLimiteDeTresSeLibera15MinutosDespues() {
        pedir();
        pedir();
        pedir();
        reloj.avanzar(Duration.ofMinutes(15).plusSeconds(1));

        pedir();

        assertThat(enviador.enviados).hasSize(4);
        assertThat(ultimoMotivo()).isEqualTo("RECUPERACION_SOLICITADA");
    }

    @Test
    void onceDesdeLaMismaIp() {
        // Diez solicitudes de este origen en la última hora, de correos que no existen.
        for (int i = 0; i < 10; i++) {
            bitacora(null, "rec3-otro" + i + "-" + marca + "@ejemplo.co", "RECUPERACION_CORREO_INEXISTENTE",
                    origen, ahora().minusMinutes(10));
        }

        pedir();

        verificarSinEnvioNiCodigo("RECUPERACION_LIMITE");
    }

    @Test
    void nueveDesdeLaMismaIpTodaviaPasan() {
        for (int i = 0; i < 9; i++) {
            bitacora(null, "rec3-otro" + i + "-" + marca + "@ejemplo.co", "RECUPERACION_CORREO_INEXISTENTE",
                    origen, ahora().minusMinutes(10));
        }

        pedir();

        assertThat(enviador.enviados).hasSize(1);
        assertThat(ultimoMotivo()).isEqualTo("RECUPERACION_SOLICITADA");
    }

    @Test
    void lasSolicitudesDeHaceMasDeUnaHoraNoCuentanParaLaIp() {
        for (int i = 0; i < 11; i++) {
            bitacora(null, "rec3-otro" + i + "-" + marca + "@ejemplo.co", "RECUPERACION_CORREO_INEXISTENTE",
                    origen, ahora().minusMinutes(61));
        }

        pedir();

        assertThat(enviador.enviados).hasSize(1);
    }

    @Test
    void elTopePorIpCuentaConUnOrigenMasLargoQueLaColumna() {
        // Un origen de 100 caracteres se recorta a 60 una sola vez, al entrar: la bitácora guarda el
        // valor de 60 y el conteo busca ese mismo valor. Con otro recorte, el tope no vería las filas.
        String largo = ("t3-" + marca + "-").repeat(8);
        String recortado = largo.substring(0, 60);
        for (int i = 0; i < 10; i++) {
            servicio.solicitar("rec3-nadie" + i + "-" + marca + "@ejemplo.co", largo);
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM auditoria_accesos WHERE origen = :origen")
                .param("origen", recortado).query(Integer.class).single()).isEqualTo(10);

        servicio.solicitar(correo, largo);

        assertThat(enviador.enviados).isEmpty();
        assertThat(ultimoMotivo()).isEqualTo("RECUPERACION_LIMITE");
        jdbc.sql("DELETE FROM auditoria_accesos WHERE origen = :origen").param("origen", recortado).update();
    }

    @Test
    void diezFallosEn24h() {
        for (int i = 0; i < 10; i++) {
            bitacora(usuarioId, correo, "RECUPERACION_CODIGO_INVALIDO", "ant-" + marca, ahora().minusHours(1));
        }

        pedir();

        assertThat(enviador.enviados).isEmpty();
        assertThat(codigosDelUsuario()).isZero();
        assertThat(ultimoMotivo()).isEqualTo("RECUPERACION_LIMITE");
    }

    @Test
    void nueveFallosEn24hTodaviaPasan() {
        for (int i = 0; i < 9; i++) {
            bitacora(usuarioId, correo, "RECUPERACION_CODIGO_INVALIDO", "ant-" + marca, ahora().minusHours(1));
        }

        pedir();

        assertThat(enviador.enviados).hasSize(1);
    }

    @Test
    void losFallosDeHaceMasDe24HorasNoCuentan() {
        for (int i = 0; i < 10; i++) {
            bitacora(usuarioId, correo, "RECUPERACION_CODIGO_INVALIDO", "ant-" + marca,
                    ahora().minusHours(24).minusMinutes(1));
        }

        pedir();

        assertThat(enviador.enviados).hasSize(1);
    }

    // ----------------------------------------------------- un código reemplaza al otro

    @Test
    void nuevoInvalidaAnterior() {
        pedir();
        pedir();

        List<Map<String, Object>> filas = jdbc.sql("SELECT codigo_id, usado_en FROM codigos_recuperacion "
                + "WHERE usuario_id = :usuario ORDER BY codigo_id").param("usuario", usuarioId).query().listOfRows();
        assertThat(filas).hasSize(2);
        assertThat(filas.get(0).get("usado_en")).as("el primero deja de servir").isNotNull();
        assertThat(filas.get(1).get("usado_en")).as("el último queda vigente").isNull();
    }

    // ----------------------------------------- el correo sale solo si la transacción se confirma

    @Test
    void elCorreoSaleDespuesDelCommitYNoAntes() {
        new TransactionTemplate(transacciones).executeWithoutResult(estado -> {
            pedir();
            assertThat(enviador.enviados).as("antes del commit no sale nada").isEmpty();
        });

        assertThat(enviador.enviados).hasSize(1);
    }

    @Test
    void siLaTransaccionRevierteNoSaleElCorreoNiQuedaElCodigo() {
        new TransactionTemplate(transacciones).executeWithoutResult(estado -> {
            pedir();
            estado.setRollbackOnly();
        });

        assertThat(enviador.enviados).isEmpty();
        assertThat(codigosDelUsuario()).isZero();
    }

    @Test
    void laTransaccionEmpiezaEnElHiloDeLaTareaYNoEnElDeLaPeticion() {
        // Si el proxy transaccional quedara por fuera del asíncrono, la transacción (y la conexión a
        // la base) se abriría en el hilo de la petición antes de entregar la tarea.
        pedir();

        assertThat(ejecutor.transaccionAlEntregar).isFalse();
        assertThat(enviador.enviados).hasSize(1);
    }

    @Test
    void unFalloDelEnvioNoSePropagaNiEscribeElCorreoNiElCodigoEnElLog() {
        String mensajeDelFallo = "no se pudo enviar a " + correo + " el código 482913";
        enviador.fallo = new IllegalStateException(mensajeDelFallo);
        Logger raiz = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> eventos = new ListAppender<>();
        eventos.start();
        raiz.addAppender(eventos);
        Level anterior = raiz.getLevel();
        raiz.setLevel(Level.ALL);
        try {
            pedir();
        } finally {
            raiz.detachAppender(eventos);
            raiz.setLevel(anterior);
            eventos.stop();
        }

        // La solicitud quedó registrada igual: el fallo del envío no cambia lo que se hizo.
        assertThat(ultimoMotivo()).isEqualTo("RECUPERACION_SOLICITADA");
        StringBuilder log = new StringBuilder();
        for (ILoggingEvent e : eventos.list) {
            if (e.getLoggerName().startsWith("co.com.bspstore") || e.getLoggerName().startsWith("org.springframework.transaction")) {
                log.append(e.getFormattedMessage()).append('\n');
                if (e.getThrowableProxy() != null) {
                    log.append(e.getThrowableProxy().getMessage()).append('\n');
                }
            }
        }
        assertThat(log.toString()).doesNotContain(correo).doesNotContain("482913").doesNotContain(mensajeDelFallo);
    }
}
