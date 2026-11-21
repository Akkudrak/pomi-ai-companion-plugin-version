// Local fixture based on PomiVoiceLink's existing Kokoro request contract.
// Returns one second of synthetic 24 kHz, signed 16-bit LE mono audio.
const http = require('http');
http.createServer((req, res) => {
  if (req.method !== 'POST' || req.url !== '/v1/audio/speech') {
    res.writeHead(501).end('Endpoint not modeled');
    return;
  }
  let body = '';
  req.on('data', chunk => { body += chunk; });
  req.on('end', () => {
    try {
      const data = JSON.parse(body);
      if (data.model !== 'kokoro' || data.response_format !== 'pcm' || data.stream !== false ||
          data.voice !== 'ef_dora' || data.speed !== 0.95 || data.input !== 'Hola, soy Pomi.') {
        res.writeHead(400).end('Unexpected speech request');
        return;
      }
      const audio = Buffer.alloc(24000 * 2);
      for (let i = 0; i < 24000; i++) {
        audio.writeInt16LE(Math.round(Math.sin(i * 2 * Math.PI * 440 / 24000) * 4000), i * 2);
      }
      res.writeHead(200, { 'Content-Type': 'application/octet-stream' }).end(audio);
    } catch {
      res.writeHead(400).end('Invalid JSON');
    }
  });
}).listen(process.env.PORT);
