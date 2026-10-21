package co.com.bspstore.sgip.logica;

/**
 * Una fila tal como venía en el archivo del proveedor, sin interpretar.
 *
 * El costo viaja como texto a propósito. Si el lector lo convirtiera a
 * número, una celda con «1.2OO» —con letra O en lugar de cero, que es lo
 * que produce una hoja retocada a mano— se perdería en el lector y el
 * RF-26 no podría decir qué decía esa celda. El requisito pide informar
 * el registro rechazado «con su causa», y la causa de un costo no
 * numérico es el texto que había.
 *
 * `numeroFila` es el número que la persona ve en su hoja de cálculo,
 * contando desde 1 e incluyendo el encabezado. Es el único dato con el
 * que puede ir a corregir el archivo.
 */
public record FilaLeida(
        int numeroFila,
        String referencia,
        String descripcion,
        String costoCrudo
) {}
