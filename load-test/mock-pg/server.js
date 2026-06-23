const express = require('express');
const http = require('http');

const app = express();
const payments = new Map();
let serverErrorMode = false;

const SERVER_WEBHOOK_URL =
  process.env.SERVER_WEBHOOK_URL || 'http://host.docker.internal:8080/payments/webhook';
const PORT = process.env.PORT || 8090;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

app.use(express.json());

app.post('/control/payments/:id/complete', (req, res) => {
  const { amount, delayMs, webhookDelayMs } = req.body;
  const { id } = req.params;

  payments.set(id, {
    status: 'PAID',
    amount,
    delayMs: delayMs || 0,
  });

  if (webhookDelayMs > 0) {
    setTimeout(() => {
      const body = JSON.stringify({ paymentId: id, status: 'PAID' });
      const webhookUrl = new URL(SERVER_WEBHOOK_URL);
      const request = http.request(
        webhookUrl,
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Content-Length': Buffer.byteLength(body),
          },
        },
        (response) => response.resume(),
      );

      request.on('error', () => {});
      request.end(body);
    }, webhookDelayMs);
  }

  res.json({ ok: true });
});

app.post('/control/payments/:id/fail', (req, res) => {
  payments.set(req.params.id, {
    status: 'FAILED',
    amount: 0,
    delayMs: 0,
  });

  res.json({ ok: true });
});

app.post('/control/server-error', (req, res) => {
  serverErrorMode = true;
  res.json({ ok: true });
});

app.post('/control/reset', (req, res) => {
  payments.clear();
  serverErrorMode = false;
  res.json({ ok: true });
});

app.get('/payments/:id', async (req, res) => {
  if (serverErrorMode) {
    return res.status(500).json({ message: 'PG server error' });
  }

  const payment = payments.get(req.params.id);
  if (!payment) {
    return res.status(404).json({ message: 'Payment not found' });
  }

  await sleep(payment.delayMs);

  if (payment.status === 'PAID') {
    return res.json({
      status: 'PAID',
      amount: { total: payment.amount },
      paidAt: new Date().toISOString(),
    });
  }

  return res.json({
    status: payment.status,
    amount: { total: 0 },
    paidAt: null,
  });
});

app.post('/payments/:id/cancel', (req, res) => {
  const payment = payments.get(req.params.id);
  if (!payment) {
    return res.status(404).json({ message: 'Payment not found' });
  }

  payment.status = 'CANCELLED';
  return res.json({ ok: true });
});

const server = app.listen(PORT, () => console.log(`Mock PG listening on port ${PORT}`));
server.keepAliveTimeout = 120000;   // 2분 (기본 5초 → 테스트 시간 커버)
server.headersTimeout = 121000;