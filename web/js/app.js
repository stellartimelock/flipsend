import { createIdentity } from './cert.js'
import { createClient } from './client.js'
import { contentType, usageLabel } from './protocol.js'
import { idbDelete, idbGet } from './vault.js'

const $ = (id) => document.getElementById(id)
const QUIET = new Set(['', 'Ready', 'Waiting for a device'])
let identity = null
let peer = null
let client = null
let inbox = []
let scanning = null
let refreshTimer = 0
let pairingOpen = false

function defaultName() {
  return /iPad|iPhone|iPod/.test(navigator.userAgent) ? 'iPhone' : 'Web'
}

function ask(title, body) {
  $('askTitle').textContent = title
  $('askBody').textContent = body || ''
  $('askBody').hidden = !body
  $('ask').hidden = false
  return new Promise((resolve) => {
    const finish = (value) => {
      $('ask').hidden = true
      $('askYes').onclick = null
      $('askNo').onclick = null
      resolve(value)
    }
    $('askYes').onclick = () => finish(true)
    $('askNo').onclick = () => finish(false)
  })
}

function setStatus(text) {
  const value = text || ''
  const hide = !peer && QUIET.has(value)
  $('status').textContent = hide ? '' : value
  $('status').hidden = hide || !value
}

async function forgetOldLock() {
  try {
    const stored = await idbGet('identity')
    if (stored?.ct || stored?.kdf || stored?.salt) await idbDelete('identity')
    await idbDelete('inbox')
    await idbDelete('vault')
    await idbDelete('sealed')
  } catch {
    /* IndexedDB may be blocked */
  }
}

async function boot() {
  await forgetOldLock()
  identity = await createIdentity(defaultName())
  peer = null
  inbox = []
  startApp()
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js').catch(() => {})
  }
  if (location.hash === '#connect') openPairing()
}

function startApp() {
  client = createClient({
    identity,
    getPeer: () => peer,
    setPeer: async (next) => {
      peer = next
      renderPeer()
    },
    relayUrl: () => undefined,
    hooks: {
      status: setStatus,
      meter: (meter) => {
        const text = meter?.limit ? usageLabel(meter.used, meter.limit) : ''
        $('usageLine').textContent = text
        $('usageLine').hidden = !text
      },
      progress: (progress) => {
        const pct = progress.total ? Math.round(progress.done * 100 / progress.total) : 0
        const batch = progress.count > 1 ? ` ${progress.index} of ${progress.count}` : ''
        const text = `${progress.state === 'receiving' ? 'Receiving' : 'Sending'} ${progress.name}${batch}: ${pct}%`
        $('progress').textContent = text
        $('progress').hidden = !text
      },
      confirmPair: ({ name }) => ask('Connect to ' + name + '?'),
      confirmOffer: (offer) => ask('Receive ' + offer.name + '?'),
      received: async (item) => {
        inbox.unshift({ id: item.id, name: item.name, kind: item.kind, at: Date.now() })
        addFile(item)
        $('progress').textContent = 'Received ' + (item.name || 'file') + '. Open it below, or save it to Photos.'
        $('progress').hidden = false
        $('inboxCard').hidden = false
      },
      disconnected: (reason) => {
        setStatus(reason || 'Disconnected.')
      },
      sessionDone: () => endSession('Sent. Connect again if you need to send another file.')
    }
  })
  renderInbox()
  renderPeer()
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      client.nudge()
      if (pairingOpen && !peer && !scanning) showOurCode()
    }
  })
  window.addEventListener('pageshow', () => client.nudge())
}

function openPairing() {
  pairingOpen = true
  $('app').hidden = false
  $('connectIphone').hidden = true
  if (!peer) showOurCode()
  $('app').scrollIntoView({ behavior: 'smooth', block: 'start' })
}

async function endSession(message) {
  pairingOpen = false
  scanning?.()
  try { await client?.unpair() } catch { /* already closed */ }
  peer = null
  inbox = []
  hideCode()
  stopRefresh()
  $('app').hidden = true
  $('connectIphone').hidden = false
  $('sendCard').hidden = true
  $('inboxCard').hidden = true
  $('connectedArea').hidden = true
  $('connectArea').hidden = false
  $('files').value = ''
  $('chosenName').hidden = true
  $('sendHow').hidden = true
  $('textBox').hidden = true
  $('retry').hidden = true
  $('progress').textContent = ''
  $('progress').hidden = true
  setStatus(message || '')
  if (message) {
    $('status').textContent = message
    $('status').hidden = false
    $('app').hidden = false
    $('connectArea').hidden = true
  }
}

$('connectIphone').onclick = () => {
  $('status').hidden = true
  $('connectArea').hidden = false
  openPairing()
}

function renderPeer() {
  const paired = !!peer
  $('peerLine').textContent = paired ? 'Connected to ' + peer.name : ''
  $('peerLine').hidden = !paired
  $('connectArea').hidden = paired
  $('connectedArea').hidden = !paired
  $('sendCard').hidden = !paired
  $('inboxCard').hidden = !paired || inbox.length === 0
  if (paired) {
    stopRefresh()
    scanning?.()
    hideCode()
    $('app').hidden = false
  } else if (client && pairingOpen) {
    showOurCode()
  }
}

function hideCode() {
  $('qr').hidden = true
}

function showOurCode() {
  if (!client || peer) return
  drawQr(client.showPairing())
  stopRefresh()
  refreshTimer = setInterval(() => {
    if (!peer && !scanning) drawQr(client.showPairing())
  }, 90000)
}

function stopRefresh() {
  clearInterval(refreshTimer)
  refreshTimer = 0
}

function drawQr(text) {
  const qr = globalThis.qrcode(0, 'M')
  qr.addData(text)
  qr.make()
  const count = qr.getModuleCount()
  const scale = 8
  const canvas = $('qr')
  canvas.width = count * scale
  canvas.height = count * scale
  const ctx = canvas.getContext('2d')
  for (let row = 0; row < count; row++) {
    for (let col = 0; col < count; col++) {
      ctx.fillStyle = qr.isDark(row, col) ? '#0B1020' : '#E8EEF9'
      ctx.fillRect(col * scale, row * scale, scale, scale)
    }
  }
  canvas.hidden = false
}

async function startScan() {
  $('photoBtn').hidden = true
  let stream
  try {
    stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })
  } catch {
    $('photoBtn').hidden = false
    $('photo').click()
    return
  }
  hideCode()
  const video = $('camera')
  video.srcObject = stream
  video.hidden = false
  $('scanCode').hidden = true
  $('stopScan').hidden = false
  await video.play()
  const canvas = document.createElement('canvas')
  const ctx = canvas.getContext('2d', { willReadFrequently: true })
  let running = true
  scanning = () => {
    if (!running) return
    running = false
    stream.getTracks().forEach((track) => track.stop())
    video.hidden = true
    $('stopScan').hidden = true
    $('scanCode').hidden = false
    scanning = null
    if (!peer && pairingOpen) showOurCode()
  }
  const tick = () => {
    if (!running) return
    if (video.videoWidth) {
      canvas.width = video.videoWidth
      canvas.height = video.videoHeight
      ctx.drawImage(video, 0, 0)
      const image = ctx.getImageData(0, 0, canvas.width, canvas.height)
      const code = globalThis.jsQR(image.data, image.width, image.height)
      if (code?.data?.includes('stellarsend://pair')) {
        scanning()
        pair(code.data)
        return
      }
    }
    requestAnimationFrame(tick)
  }
  requestAnimationFrame(tick)
}

$('scanCode').onclick = () => startScan()
$('stopScan').onclick = () => scanning?.()
$('photoBtn').onclick = () => $('photo').click()

$('photo').onchange = async () => {
  const file = $('photo').files?.[0]
  if (!file) return
  const bitmap = await createImageBitmap(file)
  const canvas = document.createElement('canvas')
  canvas.width = bitmap.width
  canvas.height = bitmap.height
  const ctx = canvas.getContext('2d')
  ctx.drawImage(bitmap, 0, 0)
  const image = ctx.getImageData(0, 0, canvas.width, canvas.height)
  const code = globalThis.jsQR(image.data, image.width, image.height)
  if (!code?.data) {
    setStatus('No code in that photo.')
    return
  }
  pair(code.data)
}

async function pair(text) {
  try {
    $('retry').hidden = true
    await client.pairFromQr(text)
    hideCode()
  } catch (error) {
    setStatus(error.message || 'Could not connect.')
    $('retry').hidden = false
    if (!peer && pairingOpen) showOurCode()
  }
}

$('forget').onclick = async () => {
  await client.unpair()
  endSession('Disconnected. Connect again if you need to send another file.')
}

$('pickFile').onclick = () => $('files').click()

$('files').onchange = () => {
  const files = $('files').files
  $('chosenName').textContent = files?.length ? [...files].map((file) => file.name).join(', ') : ''
  $('chosenName').hidden = !files?.length
  $('sendHow').hidden = !files?.length
}

$('showText').onclick = () => {
  $('textBox').hidden = !$('textBox').hidden
}

$('sendFiles').onclick = async () => {
  const files = $('files').files
  if (!files?.length) {
    setStatus('Choose a file first.')
    return
  }
  await run(() => client.sendFiles(files))
}

$('sendText').onclick = async () => {
  const text = $('text').value
  if (!text) {
    setStatus('Enter text first.')
    return
  }
  await run(() => client.sendText(text))
}

$('retry').onclick = () => run(() => client.retry())

$('pairTyped')?.addEventListener('click', () => {
  const code = $('typeCode').value.trim()
  if (!code) {
    setStatus('Type the code shown on the other device.')
    return
  }
  pair(code)
})

async function run(work) {
  $('retry').hidden = true
  try {
    await work()
  } catch (error) {
    setStatus(error.message || 'The transfer stopped.')
    $('retry').hidden = false
  }
}

function renderInbox() {
  $('inbox').replaceChildren()
  $('inboxCard').hidden = !peer || inbox.length === 0
  for (const item of inbox) addRow(item)
}

function addFile(item) {
  $('inboxCard').hidden = !peer
  addRow(item, item.blob)
}

function addRow(item, blob) {
  const row = document.createElement('div')
  row.className = 'file'
  const name = document.createElement('span')
  name.textContent = item.name
  const send = document.createElement('button')
  send.type = 'button'
  send.textContent = 'Send'
  send.onclick = () => sendReceived(item, blob)
  const open = document.createElement('button')
  open.type = 'button'
  open.textContent = 'Open'
  open.onclick = () => openReceived(item, blob)
  const keep = document.createElement('button')
  keep.type = 'button'
  keep.textContent = /iPad|iPhone|iPod/.test(navigator.userAgent) ? 'Save to Photos' : 'Save'
  keep.onclick = () => save(item, blob)
  row.append(name, open, send, keep)
  $('inbox').prepend(row)
}

async function sendReceived(item, blob) {
  try {
    const file = blob instanceof File ? blob : await fileFromStore(item)
    await run(() => client.sendFiles([file]))
  } catch (error) {
    setStatus(error.message || 'That file is no longer in this browser.')
  }
}

async function openReceived(item, blob) {
  try {
    const file = blob || await fileFromStore(item)
    const url = URL.createObjectURL(file)
    window.open(url, '_blank', 'noopener')
    setTimeout(() => URL.revokeObjectURL(url), 60000)
  } catch (error) {
    setStatus(error.message || 'That file is no longer in this browser.')
  }
}

async function save(item, blob) {
  try {
    const file = blob || await fileFromStore(item)
    const ios = /iPad|iPhone|iPod/.test(navigator.userAgent)
    if (ios && navigator.share && navigator.canShare?.({ files: [file] })) {
      await navigator.share({ files: [file] })
      return
    }
    const url = URL.createObjectURL(file)
    const link = document.createElement('a')
    link.href = url
    link.download = item.name
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 60000)
  } catch (error) {
    setStatus(error.message || 'That file is no longer in this browser.')
  }
}

async function fileFromStore(item) {
  const { openStore } = await import('./files.js')
  const store = await openStore()
  const blob = await store.file(item.id)
  return new File([blob], item.name, { type: contentType(item.name, blob.type) })
}

boot()
