// Sidecar Baileys: mantém a sessão de UM número, baixa a mídia e posta cada evento cru para o backend.
// Sem lógica de negócio. NUNCA envia mensagem, nem marca como lida, nem fica online.
// Contrato v1: docs/contrato-sidecar.md
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import makeWASocket, {
  BufferJSON,
  DisconnectReason,
  downloadMediaMessage,
  getContentType,
  isLidUser,
  isPnUser,
  jidNormalizedUser,
  normalizeMessageContent,
  useMultiFileAuthState,
} from 'baileys'
import { contarRejeitados, enfileirar, rodar } from './fila.js'

const {
  NUMERO,
  BACKEND_URL = 'http://backend:8080/interno/eventos',
  SEGREDO_ARQUIVO = '/run/secrets/sidecar_segredo',
  SESSAO_DIR = '/sessao',
  FILA_DIR = '/fila',
} = process.env

const segredo = readFileSync(SEGREDO_ARQUIVO, 'utf8').trim()
if (!/^\d+$/.test(NUMERO ?? '') || segredo.length < 32) {
  console.error('NUMERO (só dígitos, com DDI) e segredo de 32+ caracteres são obrigatórios')
  process.exit(1)
}

const MIDIA = new Set(['imageMessage', 'videoMessage', 'audioMessage', 'documentMessage', 'stickerMessage'])

const evento = (e) => JSON.stringify({ v: 1, numero: NUMERO, ...e }, BufferJSON.replacer)
const falhou = (oque) => (e) => console.error(`ERRO ao enfileirar ${oque}:`, e) // disco cheio etc.: precisa de alerta

async function receber(msg) {
  const conteudo = normalizeMessageContent(msg.message)
  const tipo = getContentType(conteudo)
  const jid = msg.key?.remoteJid
  let arquivo = null
  let midia = null
  let midiaErro = null
  // grupo é descartado pelo backend; não vale baixar a mídia dele
  if (MIDIA.has(tipo) && (isPnUser(jid) || isLidUser(jid))) {
    try {
      // sem reuploadRequest: pedir reenvio ao celular de origem é tráfego ativo
      arquivo = await downloadMediaMessage(msg, 'buffer', {})
      midia = {
        mime: conteudo[tipo].mimetype ?? 'application/octet-stream',
        sha256: createHash('sha256').update(arquivo).digest('hex'),
      }
    } catch (e) {
      midiaErro = String(e?.message ?? e)
    }
  }
  await enfileirar(FILA_DIR, evento({ tipo: 'mensagem', mensagem: msg, midia, midiaErro }), arquivo)
}

async function conectar() {
  const { state, saveCreds } = await useMultiFileAuthState(SESSAO_DIR)
  const sock = makeWASocket({ auth: state, markOnlineOnConnect: false, syncFullHistory: false })
  let codigoPedido = false

  sock.ev.on('creds.update', saveCreds)

  sock.ev.on('connection.update', async ({ connection, lastDisconnect, qr }) => {
    if (qr && !sock.authState.creds.registered && !codigoPedido) {
      codigoPedido = true
      const codigo = await sock.requestPairingCode(NUMERO)
      console.log(`PAREAMENTO: no celular, Aparelhos conectados > Conectar com número de telefone > código ${codigo}`)
    }
    if (connection) {
      console.log(`conexão: ${connection}`)
      const lid = connection === 'open' ? sock.user?.lid : undefined // backend descarta conversa entre números de trabalho
      enfileirar(FILA_DIR, evento({ tipo: 'conexao', estado: connection, lid })).catch(falhou('conexão'))
    }
    if (connection === 'open' && jidNormalizedUser(sock.user.id) !== `${NUMERO}@s.whatsapp.net`) {
      console.error(`a sessão é de ${sock.user.id}, não de ${NUMERO}`)
      process.exit(1)
    }
    if (connection === 'close') {
      if (lastDisconnect?.error?.output?.statusCode === DisconnectReason.loggedOut) {
        console.error('sessão desconectada pelo celular: apague o volume da sessão e pareie de novo')
        process.exit(1)
      }
      setTimeout(() => conectar().catch(falhou('reconexão')), 2000)
    }
  })

  const receberTodas = ({ messages }) => {
    for (const m of messages) receber(m).catch(falhou(`mensagem ${m.key?.id}`))
  }
  sock.ev.on('messages.upsert', receberTodas)
  sock.ev.on('messaging-history.set', receberTodas) // histórico recente e ressincronização

  sock.ev.on('lid-mapping.update', ({ lid, pn }) => {
    enfileirar(FILA_DIR, evento({ tipo: 'lid', lid, pn })).catch(falhou('mapa @lid'))
  })
}

// Pulso pela mesma fila: se ele atrasa no backend, o sidecar parou ou a fila está acumulando.
setInterval(async () => {
  const rejeitados = await contarRejeitados(FILA_DIR)
  enfileirar(FILA_DIR, evento({ tipo: 'pulso', rejeitados })).catch(falhou('pulso'))
}, 60_000)

rodar(FILA_DIR, { url: BACKEND_URL, segredo })
await conectar()
