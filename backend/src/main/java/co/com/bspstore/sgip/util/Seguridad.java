package co.com.bspstore.sgip.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class Seguridad {

    private static final String ALGORITMO = "PBKDF2WithHmacSHA256";
    private static final int ITERACIONES = 120_000;
    private static final int BYTES_SAL = 16;
    private static final int BITS_HASH = 256;
    private static final int BYTES_TOKEN = 32;
    private static final String PREFIJO = "pbkdf2";
    private static final SecureRandom ALEATORIO = new SecureRandom();

    private Seguridad() {
        // Clase de utilidad: no se instancia
    }

    public static String derivar(String clave) {
        byte[] sal = new byte[BYTES_SAL];
        ALEATORIO.nextBytes(sal);
        byte[] hash = pbkdf2(clave.toCharArray(), sal, ITERACIONES);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return PREFIJO + "$" + ITERACIONES + "$"
                + b64.encodeToString(sal) + "$" + b64.encodeToString(hash);
    }

    public static boolean verificar(String clave, String almacenado) {
        if (clave == null || almacenado == null) {
            return false;
        }
        String[] partes = almacenado.split("\\$");
        if (partes.length != 4 || !PREFIJO.equals(partes[0])) {
            return false;
        }
        try {
            int iteraciones = Integer.parseInt(partes[1]);
            byte[] sal = Base64.getDecoder().decode(partes[2]);
            byte[] esperado = Base64.getDecoder().decode(partes[3]);
            byte[] calculado = pbkdf2(clave.toCharArray(), sal, iteraciones);
            return MessageDigest.isEqual(esperado, calculado);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Token de sesión: 256 bits de azar criptográfico en Base64 seguro
     * para URL. No lleva dentro ninguna información del usuario, de modo
     * que no hay nada que leer en él ni nada que verificar: vale si está
     * en la tabla de sesiones y no vale si no está.
     */
    public static String nuevoToken() {
        byte[] bytes = new byte[BYTES_TOKEN];
        ALEATORIO.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Hash del token para guardarlo. SHA-256 a secas, sin sal ni
     * iteraciones, y no es una incoherencia con el PBKDF2 de arriba: una
     * contraseña la elige una persona y se puede adivinar por fuerza
     * bruta, mientras que este token es azar puro de 256 bits. Estirarlo
     * no lo haría más difícil de adivinar y costaría en cada petición.
     *
     * Devuelve 64 caracteres hexadecimales, que es justo lo que la
     * columna token_hash declara como CHAR(64).
     */
    public static String hashToken(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible en esta JVM", e);
        }
    }

    private static byte[] pbkdf2(char[] clave, byte[] sal, int iteraciones) {
        PBEKeySpec spec = new PBEKeySpec(clave, sal, iteraciones, BITS_HASH);
        try {
            return SecretKeyFactory.getInstance(ALGORITMO)
                    .generateSecret(spec)
                    .getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 no disponible en esta JVM", e);
        } finally {
            spec.clearPassword();
        }
    }
}