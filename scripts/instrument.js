// Sentry init — importe este arquivo ANTES de qualquer outro require em scripts Node.js.
// Uso: node --require ./scripts/instrument.js scripts/seu-script.js
//
// Variáveis de ambiente:
//   SENTRY_DSN          — DSN do projeto. Sem isso, Sentry fica desligado (sem erro).
//   SENTRY_ENVIRONMENT  — ex: "prod", "staging", "dev" (default: "dev")
//   SENTRY_RELEASE      — ex: "flowfuel@1.0.0" (opcional)

const Sentry = require("@sentry/node");

const dsn = process.env.SENTRY_DSN;

if (!dsn) {
  console.warn("[sentry] SENTRY_DSN ausente — monitoramento desligado.");
} else {
  Sentry.init({
    dsn,
    environment: process.env.SENTRY_ENVIRONMENT || "dev",
    release: process.env.SENTRY_RELEASE,
    tracesSampleRate: 0,
  });
  console.info(`[sentry] ativo (environment=${process.env.SENTRY_ENVIRONMENT || "dev"})`);
}
