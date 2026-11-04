/**
 * La vista previa del precio y el porcentaje en el formulario.
 *
 * La tabla no se copia aquí: es la misma que leen `verificar_precios.sh`
 * contra la vista y `CalculoDePrecioTest` contra Java. Si este cálculo se
 * aparta de los otros dos por un peso, la pantalla promete una cifra y la
 * base vende otra, y es esta prueba la que lo dice.
 */
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

import { porcentajeDesdePrecio, precioDesdePorcentaje } from './precio.js'

const TABLA = resolve(dirname(fileURLToPath(import.meta.url)),
  '../../../database/muestras/casos_de_precio.csv')

const casos = readFileSync(TABLA, 'utf8').trim().split(/\r?\n/).slice(1).map((linea) => {
  const [tipo, costo, entrada, esperado, ...nota] = linea.split(',')
  return { tipo, costo, entrada, esperado, nota: nota.join(',') }
})

describe('la tabla de casos compartida con la vista y con Java', () => {
  it('se leyó entera', () => {
    expect(casos.length).toBeGreaterThanOrEqual(22)
    expect(casos.filter((c) => c.tipo === 'precio_desde_porcentaje').length).toBeGreaterThan(0)
    expect(casos.filter((c) => c.tipo === 'porcentaje_desde_precio').length).toBeGreaterThan(0)
  })

  for (const c of casos) {
    it(`${c.tipo} ${c.costo} y ${c.entrada} da ${c.esperado}: ${c.nota}`, () => {
      const calcular = c.tipo === 'precio_desde_porcentaje'
        ? precioDesdePorcentaje
        : porcentajeDesdePrecio
      // Se compara como texto: un resultado de 1201.9999 no es «casi» 1202.
      expect(calcular(c.costo, c.entrada)).toBe(c.esperado)
    })
  }
})

describe('lo que no se puede calcular devuelve vacío, no NaN ni Infinity', () => {
  it.each([
    ['costo cero', 0, 20], ['porcentaje cero', 1000, 0],
    ['costo vacío', '', 20], ['porcentaje vacío', 1000, ''],
    ['costo nulo', null, 20], ['porcentaje nulo', 1000, undefined],
    ['costo sin sentido', 'abc', 20], ['porcentaje sin sentido', 1000, 'abc'],
    ['porcentaje negativo', 1000, -5], ['tres decimales', 1000, '20.555'],
    ['notación científica', 1000, '1e3'], ['solo un punto', 1000, '.'],
  ])('precioDesdePorcentaje con %s', (_nombre, costo, porcentaje) => {
    expect(precioDesdePorcentaje(costo, porcentaje)).toBe('')
  })

  it.each([
    ['costo cero', 0, 1250], ['precio cero', 1000, 0],
    ['costo vacío', '', 1250], ['precio vacío', 1000, ''],
    ['costo nulo', null, 1250], ['precio nulo', 1000, undefined],
    ['precio sin sentido', 1000, 'abc'], ['precio negativo', 1000, -5],
  ])('porcentajeDesdePrecio con %s', (_nombre, costo, precio) => {
    expect(porcentajeDesdePrecio(costo, precio)).toBe('')
  })
})

describe('admite lo que entrega la pantalla y lo que entrega la API', () => {
  it('números y cadenas dan lo mismo', () => {
    expect(precioDesdePorcentaje(1000, 20)).toBe('1200')
    expect(precioDesdePorcentaje('1000', '20')).toBe('1200')
    expect(precioDesdePorcentaje('1000.00', '20.00')).toBe('1200')
    expect(porcentajeDesdePrecio(1000, 1250)).toBe('25.00')
  })

  it('un cero a la derecha no cuenta como decimal de más', () => {
    expect(precioDesdePorcentaje(1000, '20.500')).toBe('1205')
  })

  it('sin negativo de cero', () => {
    expect(porcentajeDesdePrecio(100000, 99999.99)).toBe('0.00')
  })
})
