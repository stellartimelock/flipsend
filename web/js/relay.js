import { joinMessage, meterFrom, QuotaError, QUOTA_MESSAGE, relayUrl, usageMessage } from './protocol.js'

const DELAYS = [0, 2000, 5000, 10000, 15000, 20000]
const BUDGET = 90000
const ATTEMPT = 20000

export function wakePlan() {
  const plan = []
  let spent = 0
  for (const delay of DELAYS) {
    if (spent + delay >= BUDGET) break
    const timeout = Math.min(ATTEMPT, BUDGET - spent - delay)
    if (timeout < 1000) break
    plan.push({ delay, timeout })
    spent += delay + timeout
  }
  return plan
}

export async function openRelay({ url, room, role, credential, signal, onWake }) {
  // url is optional so a later hub can sit in front of the same rooms.
  const endpoint = relayUrl(url)
  let last = null
  const plan = wakePlan()
  for (let i = 0; i < plan.length; i++) {
    if (signal?.aborted) throw abortError()
    if (i > 0) onWake?.()
    if (plan[i].delay) await sleep(plan[i].delay, signal)
    try {
      return await openOnce(endpoint, joinMessage(room, role, credential), plan[i].timeout, signal)
    } catch (error) {
      if (error instanceof QuotaError || error?.name === 'AbortError') throw error
      last = error
    }
  }
  const error = new Error('The relay is still waking up. Same-Wi-Fi transfers stay free. Try again in a minute.')
  error.cause = last
  throw error
}

export async function probeUsage(url, credential, signal) {
  const opened = await openOnce(relayUrl(url), usageMessage(credential), 15000, signal)
  opened.stream.close()
  return opened.meter
}

function openOnce(endpoint, hello, timeout, signal) {
  return new Promise((resolve, reject) => {
    let settled = false
    const ws = new WebSocket(endpoint)
    ws.binaryType = 'arraybuffer'
    const chunks = []
    let waiter = null
    let ended = false
    let endError = null
    const fail = (error) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      signal?.removeEventListener('abort', onAbort)
      reject(error)
    }
    const timer = setTimeout(() => {
      try { ws.close() } catch { /* already closing */ }
      fail(new Error('The relay has not woken up yet.'))
    }, timeout)
    const onAbort = () => {
      try { ws.close() } catch { /* already closing */ }
      fail(abortError())
    }
    signal?.addEventListener('abort', onAbort, { once: true })
    const wake = () => {
      if (waiter) {
        const resume = waiter
        waiter = null
        resume()
      }
    }
    ws.onopen = () => ws.send(hello)
    ws.onmessage = (ev) => {
      if (!settled) {
        if (typeof ev.data !== 'string') {
          fail(new Error('The relay did not accept the room.'))
          return
        }
        let msg
        try {
          msg = JSON.parse(ev.data)
        } catch {
          fail(new Error('The relay did not accept the room.'))
          return
        }
        settled = true
        clearTimeout(timer)
        signal?.removeEventListener('abort', onAbort)
        if (msg.ok === true) {
          const stream = {
            async read() {
              while (chunks.length === 0) {
                if (ended) {
                  if (endError) throw endError
                  return null
                }
                await new Promise((resume) => { waiter = resume })
              }
              return chunks.shift()
            },
            async write(bytes) {
              if (ws.readyState !== WebSocket.OPEN) throw new Error('The relay connection closed.')
              ws.send(bytes)
            },
            close() {
              try { ws.close() } catch { /* already closed */ }
            }
          }
          resolve({ stream, meter: meterFrom(msg) })
          return
        }
        try { ws.close() } catch { /* already closing */ }
        if (msg.error === 'quota') failAlready(reject, new QuotaError(msg.message || QUOTA_MESSAGE))
        else failAlready(reject, new Error(msg.message || 'The relay did not connect.'))
        return
      }
      if (typeof ev.data === 'string') {
        try {
          const msg = JSON.parse(ev.data)
          endError = msg.error === 'quota'
            ? new QuotaError(msg.message || QUOTA_MESSAGE)
            : new Error('The relay sent an unexpected message.')
        } catch {
          endError = new Error('The relay sent an unexpected message.')
        }
        ended = true
      } else {
        chunks.push(new Uint8Array(ev.data))
      }
      wake()
    }
    ws.onerror = () => {
      if (!settled) fail(new Error('The relay has not woken up yet.'))
      else {
        ended = true
        endError = endError || new Error('The relay connection failed.')
        wake()
      }
    }
    ws.onclose = () => {
      if (!settled) fail(new Error('The relay closed the connection.'))
      else {
        ended = true
        wake()
      }
    }
  })
}

function failAlready(reject, error) {
  reject(error)
}

function sleep(ms, signal) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort)
      resolve()
    }, ms)
    const onAbort = () => {
      clearTimeout(timer)
      reject(abortError())
    }
    if (signal?.aborted) {
      clearTimeout(timer)
      reject(abortError())
      return
    }
    signal?.addEventListener('abort', onAbort, { once: true })
  })
}

function abortError() {
  const error = new Error('Cancelled')
  error.name = 'AbortError'
  return error
}
