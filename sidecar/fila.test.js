import assert from 'node:assert/strict'
import { createHmac } from 'node:crypto'
import { mkdtemp, readdir } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { contarRejeitados, enfileirar, processar } from './fila.js'

const segredo = 'x'.repeat(32)

function backendFalso(respostas) {
  const recebidos = []
  const fetch = async (url, { body, headers }) => {
    const evento = await body.get('evento').text()
    recebidos.push({ evento, assinatura: headers['X-Assinatura'], arquivo: body.get('arquivo') })
    return new Response('', { status: respostas.shift() })
  }
  return { fetch, recebidos }
}

test('envia em ordem, assina e apaga o que o backend aceitou', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'fila-'))
  await enfileirar(dir, '{"n":1}', Buffer.from('foto'))
  await enfileirar(dir, '{"n":2}')
  const b = backendFalso([200, 200])

  assert.equal(await processar(dir, { url: 'u', segredo, fetch: b.fetch }), true)

  assert.deepEqual(b.recebidos.map((r) => r.evento), ['{"n":1}', '{"n":2}'])
  assert.equal(b.recebidos[0].assinatura, createHmac('sha256', segredo).update('{"n":1}').digest('hex'))
  assert.equal(await b.recebidos[0].arquivo.text(), 'foto')
  assert.equal(b.recebidos[1].arquivo, null)
  assert.deepEqual(await readdir(dir), [])
})

test('erro do backend para a fila e mantém o evento para reenviar', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'fila-'))
  await enfileirar(dir, '{"n":1}')
  await enfileirar(dir, '{"n":2}')

  for (const status of [500, 401, 429]) {
    const b = backendFalso([status])
    assert.equal(await processar(dir, { url: 'u', segredo, fetch: b.fetch }), false)
    assert.equal(b.recebidos.length, 1, 'não pula para o próximo evento')
    assert.equal((await readdir(dir)).filter((n) => n.endsWith('.json')).length, 2)
  }

  const fora = async () => { throw new Error('ECONNREFUSED') }
  assert.equal(await processar(dir, { url: 'u', segredo, fetch: fora }), false)
  assert.equal((await readdir(dir)).filter((n) => n.endsWith('.json')).length, 2)
})

test('evento recusado (400) vai para rejeitados e a fila segue', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'fila-'))
  await enfileirar(dir, '{"n":1}', Buffer.from('foto'))
  await enfileirar(dir, '{"n":2}')
  const b = backendFalso([400, 200])

  assert.equal(await processar(dir, { url: 'u', segredo, fetch: b.fetch }), true)

  assert.equal(b.recebidos.length, 2)
  assert.deepEqual(await readdir(dir), ['rejeitados'])
  assert.equal((await readdir(join(dir, 'rejeitados'))).length, 2) // .json e .bin
  assert.equal(await contarRejeitados(dir), 1)
})
