# ADR-0005 · La factura electrónica se emite fuera de la transacción de venta

Estado: aceptada · 2026

## Contexto

Registrar la venta y descontar existencias debe ser atómico: no puede
existir venta sin descuento ni descuento sin venta. La emisión de la
factura, en cambio, depende de un servicio externo cuya disponibilidad no
controlamos.

## Decisión

El descuento de existencias y el registro de la venta ocurren dentro de
una sola transacción de base de datos. La emisión de la factura se
ejecuta después, fuera de ella.

## Consecuencias

Un fallo del proveedor tecnológico no revierte una venta que ya ocurrió
en el mundo real: la mercancía salió y el cliente pagó.

A cambio, pueden existir ventas registradas sin factura validada. Es un
estado legítimo que el modelo representa con
`facturas_electronicas.estado`, y que exige un mecanismo de reintento y
un reporte de ventas pendientes de facturar.

La alternativa —incluir la emisión en la transacción— dejaría la base
bloqueada mientras espera respuesta de un tercero, y revertiría ventas
consumadas por un fallo de red.
