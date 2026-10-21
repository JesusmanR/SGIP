package co.com.bspstore.sgip.logica;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La interpretación del costo que viene en el archivo del proveedor.
 *
 * Es la clase que decide dinero del módulo de importación, y la que más
 * fácil se equivoca en silencio: un costo mal leído no da error en
 * ninguna parte. Entra, se aplica, y el precio de venta sale mal durante
 * semanas sin que nadie lo relacione con la carga de aquel martes.
 */
class CostosTest {

    private void vale(String crudo, String esperado) {
        Optional<BigDecimal> leido = Costos.interpretar(crudo);
        assertTrue(leido.isPresent(), "«" + crudo + "» debía interpretarse");
        assertEquals(0, leido.get().compareTo(new BigDecimal(esperado)),
                "«" + crudo + "» → " + leido.get() + ", se esperaba " + esperado);
    }

    private void noSeEntiende(String crudo) {
        assertTrue(Costos.interpretar(crudo).isEmpty(),
                "«" + crudo + "» no debía interpretarse y dio "
                        + Costos.interpretar(crudo).orElse(null));
    }


    @Test
    void unEnteroSinAdornos() {
        vale("1850000", "1850000");
        vale("0", "0");
        vale("7", "7");
    }

    @Test
    void elFormatoEspanol() {
        // Punto de millares, coma decimal: lo que exporta un Excel en
        // español, que es el que va a usar un proveedor colombiano.
        vale("1.850,00", "1850.00");
        vale("1.234.567,89", "1234567.89");
        vale("1.850,5", "1850.5");
    }

    @Test
    void elFormatoIngles() {
        vale("1,850.00", "1850.00");
        vale("1,234,567.89", "1234567.89");
    }

    @Test
    void conUnSoloSeparadorMandaLaReglaDeLosTresDigitos() {
        // Un separador seguido de exactamente tres dígitos es de
        // millares, que es la regla de cualquier hoja de cálculo.
        vale("1.850", "1850");
        vale("1,850", "1850");
        vale("1.234.567", "1234567");
        // Con cualquier otra cantidad de dígitos detrás, es decimal.
        vale("1.85", "1.85");
        vale("1,85", "1.85");
        vale("1.8", "1.8");
        vale("12.3456", "12.3456");
    }

    @Test
    void laDecoracionQueAnadeUnaHojaAlFormatear() {
        vale("$1.850.000", "1850000");
        vale("$ 1.850.000", "1850000");
        vale(" 1850000 ", "1850000");
        // Espacio duro como separador de millares, que es lo que pone
        // una hoja configurada en francés y lo que sobrevive a un
        // copiar y pegar desde un navegador.
        vale("1 850 000", "1850000");
    }

    @Test
    void losNegativosSeInterpretanParaPoderRechazarlosPorSuCausa() {
        // El RF-26 separa «costo no numérico» de «costo menor o igual a
        // cero»: son dos errores distintos del proveedor y se corrigen
        // distinto. De modo que un negativo tiene que leerse, no
        // atragantarse aquí.
        vale("-500", "-500");
        vale("(500)", "-500");
        vale("-1.850,00", "-1850.00");
    }

    @Test
    void loQueNoEsUnNumeroNoSeInventa() {
        noSeEntiende(null);
        noSeEntiende("");
        noSeEntiende("   ");
        noSeEntiende("consultar");
        noSeEntiende("N/A");
        noSeEntiende("-");
        // Una letra O donde iba un cero: lo que deja una hoja retocada a
        // mano, y lo que el lector conserva tal cual para que se pueda
        // explicar.
        noSeEntiende("1.2OO");
        noSeEntiende("1850000 pesos");
    }

    @Test
    void unSeparadorDuplicadoNoEsAgrupacionDeMillares() {
        // Estos dos entraban como 1850. Quitar el separador a secas y
        // mirar si lo que queda parece un número no basta: «1..850»
        // pierde los dos puntos y se convierte en un número
        // perfectamente válido que no es lo que decía el archivo.
        //
        // Ahora un texto solo cuenta como agrupación de millares si
        // tiene su forma exacta: uno a tres dígitos y después grupos de
        // tres.
        noSeEntiende("1..850");
        noSeEntiende("1,,850");
        noSeEntiende("1.85.3");
        noSeEntiende("1.2345.678");
    }

    @Test
    void unaAgrupacionMalFormadaSeLeeComoDecimalSiSeEntiende() {
        // «1234.567» no puede ser agrupación de millares —cuatro dígitos
        // antes del primer separador— pero como decimal se entiende sin
        // ambigüedad, de modo que se acepta en vez de rechazarse.
        vale("1234.567", "1234.567");
    }

    @Test
    void elCasoAmbiguoQueLaConvencionNoCubre() {
        // «1.850» escrito por alguien que quería 1,850 exactos en inglés
        // se lee como mil ochocientos cincuenta. No se puede distinguir,
        // y en pesos colombianos un costo con tres decimales no existe,
        // de modo que la convención de millares es la correcta aquí.
        //
        // Esta prueba no celebra el acierto: deja escrito cuál es el
        // caso que se resuelve por convención, para que quien lo
        // encuentre algún día sepa que fue una decisión y no un
        // descuido.
        vale("1.850", "1850");
    }
}
