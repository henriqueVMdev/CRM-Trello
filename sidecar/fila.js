// Fila em disco: cada evento vira um arquivo antes de ir para o backend.
// Se o backend cair, os eventos esperam aqui e são reenviados em ordem.
import { createHmac } from 'node:crypto'
import { mkdir, readdir, readFile, rename, rm, writeFile } from 'node:fs/promises'
import { join } from 'node:path'

let seq = 0

/** corpo: string JSON do evento (contrato v1). arquivo: Buffer da mídia ou null. */
export async function enfileirar(dir, corpo, arquivo = null) {
  await mkdir(dir, { recursive: true })
  const base = join(dir, `${Date.now()}-${String(seq++).padStart(9, '0')}`)
  if (arquivo) await writeFile(base + '.bin', arquivo)
  await writeFile(base + '.tmp', corpo)
  await rename(base + '.tmp', base + '.json') // só entra na fila quando está completo
}

/** Envia a fila em ordem. Retorna false quando precisa tentar de novo mais tarde. */
export async function processar(dir, { url, segredo, fetch = globalThis.fetch }) {
  await mkdir(dir, { recursive: true })
  const nomes = (await readdir(dir)).filter((n) => n.endsWith('.json')).sort()
  for (const nome of nomes) {
    const base = join(dir, nome.slice(0, -'.json'.length))
    const corpo = await readFile(base + '.json')
    const arquivo = await readFile(base + '.bin').catch(() => null)

    const form = new FormData()
    form.append('evento', new Blob([corpo], { type: 'application/json' }), 'evento.json')
    if (arquivo) form.append('arquivo', new Blob([arquivo]), 'arquivo.bin')

    let status
    try {
      const resp = await fetch(url, {
        method: 'POST',
        body: form,
        headers: { 'X-Assinatura': createHmac('sha256', segredo).update(corpo).digest('hex') },
      })
      status = resp.status
      if (status >= 400) console.error(`backend respondeu ${status} para ${nome}: ${await resp.text()}`)
    } catch (e) {
      console.error(`backend inacessível: ${e.message}`)
      return false
    }

    if (status >= 200 && status < 300) {
      await rm(base + '.json')
      await rm(base + '.bin', { force: true })
    } else if (status >= 400 && status < 500 && ![401, 408, 429].includes(status)) {
      // o backend recusou o evento: guarda à parte para análise e segue a fila
      const rejeitados = join(dir, 'rejeitados')
      await mkdir(rejeitados, { recursive: true })
      if (arquivo) await rename(base + '.bin', join(rejeitados, nome.replace('.json', '.bin')))
      await rename(base + '.json', join(rejeitados, nome))
    } else {
      return false // 401 (segredo trocado), 5xx, limite: tenta de novo depois
    }
  }
  return true
}

export async function contarRejeitados(dir) {
  const nomes = await readdir(join(dir, 'rejeitados')).catch(() => [])
  return nomes.filter((n) => n.endsWith('.json')).length
}

export async function rodar(dir, opcoes) {
  let espera = 1000
  for (;;) {
    const ok = await processar(dir, opcoes).catch((e) => (console.error(e), false))
    espera = ok ? 1000 : Math.min(espera * 2, 60_000)
    await new Promise((r) => setTimeout(r, espera))
  }
}
